package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.marketing.meta.MetaRepository.Conexao;
import br.com.sgsm.ia.security.ContextoSeguranca;
import br.com.sgsm.ia.service.AuditoriaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static br.com.sgsm.ia.marketing.meta.MetaFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaOAuthServiceTest {

    private static final UUID CLINICA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String STATE_JSON = """
            {"estabelecimentoId":"11111111-1111-1111-1111-111111111111","usuarioId":"user-1",
             "referenciaId":"admin-1","perfil":"ADMIN_ESTABELECIMENTO","email":"a@sgsm.com"}
            """;

    @Mock private MetaGraphClient graph;
    @Mock private MetaRepository repo;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valores;
    @Mock private ContextoSeguranca contexto;
    @Mock private AuditoriaService auditoria;

    private MetaTokenCryptoService crypto;
    private MetaOAuthService service;

    @BeforeEach
    void setUp() {
        var props = MetaFixtures.props();
        crypto = new MetaTokenCryptoService(props);
        service = new MetaOAuthService(props, graph, repo, crypto, redis, MetaFixtures.MAPPER, contexto, auditoria);
        lenient().when(redis.opsForValue()).thenReturn(valores);
    }

    private void stateValido() {
        when(valores.getAndDelete(MetaOAuthService.PREFIXO_STATE + "st-1")).thenReturn(STATE_JSON);
    }

    private void trocaDeCode() {
        when(graph.get(eq("/oauth/access_token"), argThat(p -> "codigo-1".equals(p.get("code"))), isNull()))
                .thenReturn(json("{\"access_token\":\"token-curto\"}"));
    }

    // ── iniciar ───────────────────────────────────────────────────────────────

    @Test
    void iniciarDeveGuardarStateDeUsoUnicoEMontarAUrlDoLoginForBusiness() {
        when(contexto.getUsuarioId()).thenReturn("user-1");
        when(contexto.getReferenciaIdStr()).thenReturn("admin-1");

        String url = service.iniciar(CLINICA);

        var chave = ArgumentCaptor.forClass(String.class);
        var valor = ArgumentCaptor.forClass(String.class);
        verify(valores).set(chave.capture(), valor.capture(), eq(MetaOAuthService.TTL_STATE));
        String state = chave.getValue().substring(MetaOAuthService.PREFIXO_STATE.length());
        assertThat(state).hasSizeGreaterThanOrEqualTo(40);
        assertThat(valor.getValue()).contains(CLINICA.toString()).contains("user-1");
        assertThat(url)
                .startsWith("https://www.facebook.com/v23.0/dialog/oauth?")
                .contains("client_id=app-123")
                .contains("config_id=config-456")
                .contains("state=" + state)
                .contains("response_type=code")
                .doesNotContain("client_secret");
    }

    @Test
    void semConfigurationIdDevePedirSoAdsRead() {
        var props = MetaFixtures.props();
        var semConfig = new MetaProperties(true, props.appId(), props.appSecret(), "", props.graphVersion(),
                props.graphBaseUrl(), props.dialogBaseUrl(), props.redirectUri(), props.frontRetornoUrl(),
                props.tokenCryptoKey(), 28, 80);
        var svc = new MetaOAuthService(semConfig, graph, repo, crypto, redis, MetaFixtures.MAPPER, contexto, auditoria);

        assertThat(svc.iniciar(CLINICA)).contains("scope=ads_read").doesNotContain("config_id");
    }

    // ── callback ──────────────────────────────────────────────────────────────

    @Test
    void stateDesconhecidoOuReutilizadoDeveSerRecusadoSemChamarAMeta() {
        when(valores.getAndDelete(anyString())).thenReturn(null);

        String destino = service.callback("codigo-1", "st-falso", null);

        assertThat(destino).isEqualTo("http://localhost:3001/trafego-pago?meta=erro&motivo=state_invalido");
        verifyNoInteractions(graph, repo);
    }

    @Test
    void semStateDeveSerRecusado() {
        assertThat(service.callback("codigo-1", null, null)).contains("motivo=state_invalido");
    }

    @Test
    void usuarioQueCancelouNaMetaVoltaComoCancelado() {
        stateValido();

        String destino = service.callback(null, "st-1", "access_denied");

        assertThat(destino).contains("meta=cancelado").contains("estabelecimento=" + CLINICA);
        verifyNoInteractions(graph, repo);
    }

    @Test
    void tokenDeUsuarioDoSistemaNaoExpiraENaoPrecisaDeTrocaPorLongaDuracao() {
        stateValido();
        trocaDeCode();
        when(graph.get(eq("/debug_token"), anyMap(), isNull())).thenReturn(json("""
                {"data":{"is_valid":true,"expires_at":0,"scopes":["ads_read","business_management"]}}
                """));
        when(graph.get(eq("/me"), anyMap(), eq("token-curto"))).thenReturn(json("{\"id\":\"999\",\"name\":\"Ana\"}"));

        String destino = service.callback("codigo-1", "st-1", null);

        assertThat(destino).contains("meta=conectado").contains("estabelecimento=" + CLINICA);
        var tokenCifrado = ArgumentCaptor.forClass(String.class);
        verify(repo).salvarConexao(eq(CLINICA), eq("999"), eq("Ana"), eq("USUARIO_SISTEMA"), tokenCifrado.capture(),
                eq("ads_read,business_management"), isNull(), eq("user-1"));
        // gravado cifrado, nunca em texto puro
        assertThat(tokenCifrado.getValue()).startsWith("v1:").doesNotContain("token-curto");
        assertThat(crypto.decifrar(tokenCifrado.getValue())).isEqualTo("token-curto");
        verify(graph, never()).get(eq("/oauth/access_token"), argThat(p -> p.containsKey("fb_exchange_token")), any());
        verify(auditoria).registrar("META_CONEXAO", CLINICA.toString(), "CRIACAO", "admin-1", "ADMIN_ESTABELECIMENTO", "a@sgsm.com");
    }

    @Test
    void tokenDeUsuarioDeveSerTrocadoPeloDeLongaDuracaoComExpiracao() {
        stateValido();
        trocaDeCode();
        when(graph.get(eq("/debug_token"), anyMap(), isNull())).thenReturn(json("""
                {"data":{"expires_at":1790000000,"scopes":["ads_read"]}}
                """));
        when(graph.get(eq("/oauth/access_token"), argThat(p -> "token-curto".equals(p.get("fb_exchange_token"))), isNull()))
                .thenReturn(json("{\"access_token\":\"token-longo\",\"expires_in\":5184000}"));
        when(graph.get(eq("/me"), anyMap(), eq("token-longo"))).thenReturn(json("{\"id\":\"999\",\"name\":\"Ana\"}"));

        service.callback("codigo-1", "st-1", null);

        var expira = ArgumentCaptor.forClass(OffsetDateTime.class);
        var token = ArgumentCaptor.forClass(String.class);
        verify(repo).salvarConexao(eq(CLINICA), any(), any(), eq("USUARIO_LONGA_DURACAO"), token.capture(), any(),
                expira.capture(), any());
        assertThat(crypto.decifrar(token.getValue())).isEqualTo("token-longo");
        assertThat(expira.getValue()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(59));
    }

    @Test
    void semExpiresInUsaAExpiracaoDoDebugToken() {
        stateValido();
        trocaDeCode();
        when(graph.get(eq("/debug_token"), anyMap(), isNull())).thenReturn(json("""
                {"data":{"expires_at":1790000000,"scopes":["ads_read"]}}
                """));
        when(graph.get(eq("/oauth/access_token"), argThat(p -> p.containsKey("fb_exchange_token")), isNull()))
                .thenReturn(json("{\"access_token\":\"token-longo\"}"));
        when(graph.get(eq("/me"), anyMap(), anyString())).thenReturn(json("{\"id\":\"999\"}"));

        service.callback("codigo-1", "st-1", null);

        verify(repo).salvarConexao(any(), any(), any(), any(), any(), any(),
                eq(java.time.Instant.ofEpochSecond(1790000000).atOffset(ZoneOffset.UTC)), any());
    }

    @Test
    void semPermissaoAdsReadNaoGravaNada() {
        stateValido();
        trocaDeCode();
        when(graph.get(eq("/debug_token"), anyMap(), isNull())).thenReturn(json("""
                {"data":{"expires_at":0,"scopes":["public_profile"]}}
                """));

        String destino = service.callback("codigo-1", "st-1", null);

        assertThat(destino).contains("meta=erro").contains("motivo=permissao_negada");
        verifyNoInteractions(repo, auditoria);
    }

    @Test
    void falhaDaMetaNaTrocaVoltaComoErroSemGravar() {
        stateValido();
        when(graph.get(eq("/oauth/access_token"), anyMap(), isNull()))
                .thenThrow(new MetaApiException(400, 100, "code expirado"));

        String destino = service.callback("codigo-1", "st-1", null);

        assertThat(destino).contains("meta=erro").contains("motivo=falha_meta");
        verifyNoInteractions(repo);
    }

    @Test
    void stateCorrompidoDeveSerRecusado() {
        when(valores.getAndDelete(anyString())).thenReturn("isso-nao-e-json");

        assertThat(service.callback("c", "st-1", null)).contains("state_invalido");
    }

    // ── status / desconectar ──────────────────────────────────────────────────

    private static Conexao conexao(String status, String tokenCifrado) {
        return new Conexao(UUID.randomUUID(), CLINICA, "999", "Ana", "USUARIO_SISTEMA", tokenCifrado, "ads_read",
                null, status, null, null);
    }

    @Test
    void statusSemConexaoDeveSerNaoConectada() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.empty());

        var status = service.status(CLINICA);

        assertThat(status.conectado()).isFalse();
        assertThat(status.status()).isEqualTo("NAO_CONECTADA");
    }

    @Test
    void statusNuncaExpoeOToken() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.of(conexao("ATIVA", crypto.cifrar("segredo"))));

        var status = service.status(CLINICA);

        assertThat(status.conectado()).isTrue();
        assertThat(status.metaUsuario()).isEqualTo("Ana");
        assertThat(status.toString()).doesNotContain("v1:").doesNotContain("segredo");
    }

    @Test
    void desconectarDeveRevogarNaMetaApagarOTokenEAuditar() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.of(conexao("ATIVA", crypto.cifrar("tk"))));

        service.desconectar(CLINICA);

        verify(graph).delete("/me/permissions", "tk");
        verify(repo).marcarStatus(CLINICA, "REVOGADA", "Desconectado pelo usuário");
        verify(auditoria).registrar("META_CONEXAO", CLINICA.toString(), "INATIVACAO");
    }

    @Test
    void falhaAoRevogarNaMetaNaoImpedeApagarOToken() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.of(conexao("ATIVA", crypto.cifrar("tk"))));
        when(graph.delete(anyString(), anyString())).thenThrow(new MetaApiException(400, 190, "token já inválido"));

        service.desconectar(CLINICA);

        verify(repo).marcarStatus(CLINICA, "REVOGADA", "Desconectado pelo usuário");
    }

    @Test
    void desconectarConexaoJaSemTokenNaoChamaAMeta() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.of(conexao("REVOGADA", null)));

        service.desconectar(CLINICA);

        verifyNoInteractions(graph);
    }

    @Test
    void desconectarSemConexaoDeveRetornar404() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.desconectar(CLINICA)).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void retornoFrontDeveCodificarOsParametros() {
        assertThat(service.retornoFront(null, "erro", "falha meta")).isEqualTo(
                "http://localhost:3001/trafego-pago?meta=erro&motivo=falha%20meta");
        assertThat(service.retornoFront("abc", "conectado", null)).isEqualTo(
                "http://localhost:3001/trafego-pago?meta=conectado&estabelecimento=abc");
    }
}

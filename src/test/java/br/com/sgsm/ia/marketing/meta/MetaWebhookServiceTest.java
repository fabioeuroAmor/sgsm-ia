package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.AcessoNegadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaWebhookServiceTest {

    private static final UUID CLINICA_1 = UUID.randomUUID();
    private static final UUID CLINICA_2 = UUID.randomUUID();

    @Mock private MetaRepository repo;
    @Mock private MetaRagIndexador ragIndexador;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valores;

    private MetaWebhookService service;

    @BeforeEach
    void setUp() {
        service = new MetaWebhookService(MetaFixtures.props(), repo, ragIndexador, redis, MetaFixtures.MAPPER);
    }

    // Monta um signed_request do jeito que a Meta monta: base64url(HMAC-SHA256(payload)).payload
    static String signedRequest(String payloadJson, String segredo) throws Exception {
        var b64 = Base64.getUrlEncoder().withoutPadding();
        String payload = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return b64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))) + "." + payload;
    }

    private static String valido() throws Exception {
        return signedRequest("{\"algorithm\":\"HMAC-SHA256\",\"user_id\":\"999\",\"issued_at\":1700000000}",
                MetaFixtures.APP_SECRET);
    }

    @Test
    void desautorizacaoDeveRevogarTodasAsClinicasDoUsuarioMeta() throws Exception {
        when(repo.estabelecimentosDoUsuarioMeta("999")).thenReturn(List.of(CLINICA_1, CLINICA_2));

        service.desautorizar(valido());

        verify(repo).marcarStatus(CLINICA_1, "REVOGADA", "App removido pelo usuário na Meta");
        verify(repo).marcarStatus(CLINICA_2, "REVOGADA", "App removido pelo usuário na Meta");
    }

    @Test
    void exclusaoDeveApagarTudoEDevolverCodigoEUrlDeStatus() throws Exception {
        when(repo.estabelecimentosDoUsuarioMeta("999")).thenReturn(List.of(CLINICA_1));
        when(redis.opsForValue()).thenReturn(valores);

        var resposta = service.excluirDados(valido());

        verify(repo).excluirTudo(CLINICA_1);
        // o resumo usado pelo assistente também é apagado
        verify(ragIndexador).remover(CLINICA_1);
        assertThat(resposta.confirmation_code()).isNotBlank();
        assertThat(resposta.url()).isEqualTo("http://localhost:3001/marketing/meta/exclusao-dados/" + resposta.confirmation_code());
        verify(valores).set(MetaWebhookService.PREFIXO_EXCLUSAO + resposta.confirmation_code(), "CONCLUIDA",
                MetaWebhookService.TTL_EXCLUSAO);
    }

    // Exclusão incompleta não pode ser confirmada à Meta
    @Test
    void falhaAoApagarOResumoNoMilvusNaoDevolveCodigo() throws Exception {
        when(repo.estabelecimentosDoUsuarioMeta("999")).thenReturn(List.of(CLINICA_1));
        doThrow(new RuntimeException("milvus fora")).when(ragIndexador).remover(CLINICA_1);

        assertThatThrownBy(() -> service.excluirDados(valido())).hasMessage("milvus fora");
        verifyNoInteractions(redis);
    }

    @Test
    void statusDaExclusaoDeveRefletirORegistro() {
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.get(MetaWebhookService.PREFIXO_EXCLUSAO + "abc")).thenReturn("CONCLUIDA");

        assertThat(service.statusExclusao("abc")).containsEntry("status", "CONCLUIDA");
        assertThat(service.statusExclusao("outro")).containsEntry("status", "NAO_ENCONTRADA");
    }

    @Test
    void assinaturaComOutroSegredoDeveSerRecusada() throws Exception {
        String forjado = signedRequest("{\"algorithm\":\"HMAC-SHA256\",\"user_id\":\"999\"}", "segredo-do-atacante");

        assertThatThrownBy(() -> service.desautorizar(forjado)).isInstanceOf(AcessoNegadoException.class);
        verifyNoInteractions(repo);
    }

    @Test
    void payloadAdulteradoDeveSerRecusado() throws Exception {
        String original = valido();
        String outroPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"algorithm\":\"HMAC-SHA256\",\"user_id\":\"123\"}".getBytes(StandardCharsets.UTF_8));
        String adulterado = original.substring(0, original.indexOf('.') + 1) + outroPayload;

        assertThatThrownBy(() -> service.excluirDados(adulterado)).isInstanceOf(AcessoNegadoException.class);
        verifyNoInteractions(repo);
    }

    @Test
    void algoritmoDiferenteDeveSerRecusado() throws Exception {
        String outroAlgoritmo = signedRequest("{\"algorithm\":\"NONE\",\"user_id\":\"999\"}", MetaFixtures.APP_SECRET);

        assertThatThrownBy(() -> service.desautorizar(outroAlgoritmo)).isInstanceOf(AcessoNegadoException.class);
    }

    @Test
    void semUserIdDeveSerRecusado() throws Exception {
        String semUsuario = signedRequest("{\"algorithm\":\"HMAC-SHA256\"}", MetaFixtures.APP_SECRET);

        assertThatThrownBy(() -> service.desautorizar(semUsuario)).isInstanceOf(AcessoNegadoException.class);
    }

    @Test
    void malformadoDeveSerRecusado() {
        assertThatThrownBy(() -> service.desautorizar(null)).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> service.desautorizar("sem-ponto")).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> service.desautorizar("@@@.###")).isInstanceOf(AcessoNegadoException.class);
    }
}

package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.OperacaoEmAndamentoException;
import br.com.sgsm.ia.marketing.meta.MetaRepository.Conexao;
import br.com.sgsm.ia.marketing.meta.MetaRepository.ContaAnuncio;
import br.com.sgsm.ia.service.TravaRedis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static br.com.sgsm.ia.marketing.meta.MetaFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaSyncServiceTest {

    private static final UUID CLINICA = UUID.randomUUID();
    private static final UUID EXECUCAO = UUID.randomUUID();
    private static final Clock HOJE = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);

    @Mock private MetaGraphClient graph;
    @Mock private MetaRepository repo;
    @Mock private TravaRedis trava;
    @Mock private MetaRagIndexador ragIndexador;

    private MetaTokenCryptoService crypto;
    private MetaSyncService service;

    @BeforeEach
    void setUp() {
        var props = MetaFixtures.props();
        crypto = new MetaTokenCryptoService(props);
        service = new MetaSyncService(props, graph, repo, crypto, new MetaNormalizador(), trava, ragIndexador, HOJE);
        lenient().when(trava.adquirir(MetaSyncService.PREFIXO_TRAVA + CLINICA, MetaSyncService.TTL_TRAVA))
                .thenReturn(Optional.of("dono"));
    }

    private void conexaoComUmaConta() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.of(new Conexao(UUID.randomUUID(), CLINICA, "999", "Ana",
                "USUARIO_SISTEMA", crypto.cifrar("tk"), "ads_read", null, "ATIVA", null, null)));
        when(repo.contasSelecionadas(CLINICA)).thenReturn(List.of(
                new ContaAnuncio("act_1", "Conta 1", "BRL", "America/Sao_Paulo", 1, true)));
        when(repo.iniciarSync(CLINICA)).thenReturn(EXECUCAO);
    }

    @Test
    void deveSincronizarHierarquiaEInsightsDosTresNiveis() {
        conexaoComUmaConta();
        when(graph.getTodos(eq("/act_1/campaigns"), anyMap(), eq("tk"))).thenReturn(List.of(json("{\"id\":\"c1\"}")));
        when(graph.getTodos(eq("/act_1/adsets"), anyMap(), eq("tk"))).thenReturn(List.of(json("{\"id\":\"s1\"}")));
        when(graph.getTodos(eq("/act_1/ads"), anyMap(), eq("tk"))).thenReturn(List.of(json("{\"id\":\"a1\"}")));
        when(graph.getTodos(eq("/act_1/insights"), argThat(p -> "campaign".equals(p.get("level"))), eq("tk")))
                .thenReturn(List.of(json(MetaFixtures.INSIGHT_CAMPANHA)));
        when(graph.getTodos(eq("/act_1/insights"), argThat(p -> !"campaign".equals(p.get("level"))), eq("tk")))
                .thenReturn(List.of());

        var resultado = service.sincronizar(CLINICA);

        assertThat(resultado).isEqualTo(new MetaSyncService.Resultado("SUCESSO", 1, 1, 1, 1, 1, false));
        verify(repo).salvarCampanha(eq(CLINICA), eq("act_1"), any());
        verify(repo).salvarConjunto(eq(CLINICA), eq("act_1"), any());
        verify(repo).salvarAnuncio(eq(CLINICA), eq("act_1"), any());
        verify(repo).salvarInsight(eq(CLINICA), argThat(i -> i.leads() == 20 && "BRL".equals(i.moeda())));
        verify(repo).finalizarSync(EXECUCAO, "SUCESSO", 1, 1, 1, 1, null);
        verify(repo).registrarSyncConcluido(CLINICA);
        verify(ragIndexador).indexar(CLINICA);
        verify(trava).liberar(MetaSyncService.PREFIXO_TRAVA + CLINICA, "dono");
    }

    @Test
    void insightsDevemReprocessarAJanelaDeAtribuicaoDiaADia() {
        var params = service.parametrosInsights("adset");

        assertThat(params).containsEntry("level", "adset")
                .containsEntry("time_increment", "1")
                .containsEntry("time_range", "{\"since\":\"2026-09-17\",\"until\":\"2026-10-15\"}");
    }

    @Test
    void tokenRecusadoPelaMetaDeveMarcarConexaoComoExpirada() {
        conexaoComUmaConta();
        when(graph.getTodos(eq("/act_1/campaigns"), anyMap(), anyString()))
                .thenThrow(new MetaApiException(400, 190, "token expirado"));

        assertThatThrownBy(() -> service.sincronizar(CLINICA)).isInstanceOf(MetaApiException.class);

        verify(repo).finalizarSync(EXECUCAO, "ERRO", 0, 0, 0, 0, "token expirado");
        verify(repo).marcarStatus(eq(CLINICA), eq("EXPIRADA"), anyString());
        verifyNoInteractions(ragIndexador);
        verify(trava).liberar(anyString(), eq("dono"));
    }

    @Test
    void outroErroDaMetaNaoMarcaExpirada() {
        conexaoComUmaConta();
        when(graph.getTodos(eq("/act_1/campaigns"), anyMap(), anyString()))
                .thenThrow(new MetaApiException(500, 2, "instável"));

        assertThatThrownBy(() -> service.sincronizar(CLINICA)).isInstanceOf(MetaApiException.class);

        verify(repo, never()).marcarStatus(any(), any(), any());
    }

    @Test
    void usoDaApiAcimaDoLimiteDeveInterromperAntesDaProximaConta() {
        conexaoComUmaConta();
        when(graph.getUltimoUsoPercentual()).thenReturn(85);

        var resultado = service.sincronizar(CLINICA);

        assertThat(resultado.interrompidoPorLimite()).isTrue();
        assertThat(resultado.status()).isEqualTo("PARCIAL");
        verify(graph, never()).getTodos(anyString(), anyMap(), anyString());
        verify(repo).finalizarSync(EXECUCAO, "PARCIAL", 0, 0, 0, 0, null);
    }

    @Test
    void syncJaEmAndamentoDeveResponder409() {
        when(trava.adquirir(anyString(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sincronizar(CLINICA)).isInstanceOf(OperacaoEmAndamentoException.class);
        verifyNoInteractions(repo, graph);
    }

    @Test
    void rodadaDoSchedulerContinuaQuandoUmaClinicaFalha() {
        UUID outra = UUID.randomUUID();
        when(repo.conexoesAtivas()).thenReturn(List.of(
                new Conexao(UUID.randomUUID(), CLINICA, null, null, "USUARIO_SISTEMA", null, null, null, "ATIVA", null, null),
                new Conexao(UUID.randomUUID(), outra, null, null, "USUARIO_SISTEMA", null, null, null, "ATIVA", null, null)));
        when(trava.adquirir(anyString(), any())).thenReturn(Optional.of("dono"));
        // as duas sem token válido: falham, mas a segunda ainda é tentada
        when(repo.buscarConexao(any())).thenReturn(Optional.empty());

        service.sincronizarTodas();

        verify(repo).buscarConexao(CLINICA);
        verify(repo).buscarConexao(outra);
    }
}

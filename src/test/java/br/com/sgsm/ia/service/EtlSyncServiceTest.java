package br.com.sgsm.ia.service;

import br.com.sgsm.ia.exception.OperacaoEmAndamentoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EtlSyncServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private DocumentoBuilder documentoBuilder;
    @Mock
    private MilvusIndexService milvusIndexService;
    @Mock
    private TravaRedis travaRedis;

    private EtlSyncService service;

    @BeforeEach
    void setUp() {
        service = new EtlSyncService(jdbc, documentoBuilder, milvusIndexService, travaRedis);
    }

    private void travaLivre() {
        when(travaRedis.adquirir(EtlSyncService.CHAVE_TRAVA, EtlSyncService.TTL_TRAVA)).thenReturn(Optional.of("token-1"));
    }

    @Test
    void deveRetornarErroParaTipoDesconhecido() {
        Map<String, Object> resultado = service.syncTipo("INEXISTENTE");

        assertThat(resultado.get("total")).isEqualTo(0);
        assertThat(resultado.get("erros")).isEqualTo(0);
        assertThat(resultado.get("erro")).isEqualTo("Tipo desconhecido: INEXISTENTE");
        verifyNoInteractions(documentoBuilder, milvusIndexService);
    }

    @Test
    void deveSincronizarTipoComSucesso() {
        when(jdbc.queryForList(contains("sgsm.paciente"), eq(String.class)))
                .thenReturn(List.of("id-1", "id-2"));
        when(documentoBuilder.construir("PACIENTE", "id-1")).thenReturn("texto-1");
        when(documentoBuilder.construir("PACIENTE", "id-2")).thenReturn("texto-2");

        Map<String, Object> resultado = service.syncTipo("PACIENTE");

        assertThat(resultado.get("total")).isEqualTo(2);
        assertThat(resultado.get("erros")).isEqualTo(0);
        verify(milvusIndexService).upsert("PACIENTE", "id-1", "texto-1");
        verify(milvusIndexService).upsert("PACIENTE", "id-2", "texto-2");
    }

    @Test
    void deveContarErrosQuandoIndexacaoFalha() {
        when(jdbc.queryForList(contains("sgsm.medico"), eq(String.class)))
                .thenReturn(List.of("id-1"));
        when(documentoBuilder.construir("MEDICO", "id-1")).thenThrow(new RuntimeException("falha"));

        Map<String, Object> resultado = service.syncTipo("MEDICO");

        assertThat(resultado.get("total")).isEqualTo(1);
        assertThat(resultado.get("erros")).isEqualTo(1);
        verifyNoInteractions(milvusIndexService);
    }

    @Test
    void deveSincronizarTodosOsTiposEAtualizarViewMaterializada() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        Map<String, Object> resultado = service.syncTodos();

        assertThat(resultado.get("total")).isEqualTo(0);
        assertThat(resultado.get("erros")).isEqualTo(0);
        verify(jdbc).execute("REFRESH MATERIALIZED VIEW crm.mv_resumo_executivo");
    }

    @Test
    void deveContinuarQuandoFalhaAtualizarViewMaterializada() {
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        doThrow(new RuntimeException("falha view")).when(jdbc).execute(anyString());

        Map<String, Object> resultado = service.syncTodos();

        assertThat(resultado.get("total")).isEqualTo(0);
    }

    @Test
    void syncAnaliticoDeveAtualizarViewEIndexarTodosOsDocumentosAnaliticos() {
        when(documentoBuilder.construirAnalitico()).thenReturn("resumo analítico");
        when(documentoBuilder.construirFaturamentoMensal()).thenReturn("faturamento mensal");
        when(documentoBuilder.construirOcupacaoAgenda()).thenReturn("ocupação agenda");
        when(documentoBuilder.construirAltoValor()).thenReturn("alto valor");
        when(documentoBuilder.construirChurnRisco()).thenReturn("churn risco");
        when(documentoBuilder.construirFunilMedico()).thenReturn("funil médico");
        when(documentoBuilder.construirCancelamentos()).thenReturn("cancelamentos");

        service.syncAnalitico();

        verify(jdbc).execute("REFRESH MATERIALIZED VIEW crm.mv_resumo_executivo");
        verify(milvusIndexService).indexarAnalitico("resumo-analitico", "resumo analítico");
        verify(milvusIndexService).indexarAnalitico("faturamento-mensal", "faturamento mensal");
        verify(milvusIndexService).indexarAnalitico("ocupacao-agenda", "ocupação agenda");
        verify(milvusIndexService).indexarAnalitico("alto-valor", "alto valor");
        verify(milvusIndexService).indexarAnalitico("churn-risco", "churn risco");
        verify(milvusIndexService).indexarAnalitico("funil-medico", "funil médico");
        verify(milvusIndexService).indexarAnalitico("cancelamentos", "cancelamentos");
    }

    @Test
    void syncAnaliticoDeveContinuarIndexandoDemaisDocumentosQuandoUmFalha() {
        when(documentoBuilder.construirAnalitico()).thenThrow(new RuntimeException("falha resumo"));
        when(documentoBuilder.construirFaturamentoMensal()).thenReturn("faturamento mensal");
        when(documentoBuilder.construirOcupacaoAgenda()).thenReturn("ocupação agenda");
        when(documentoBuilder.construirAltoValor()).thenReturn("alto valor");
        when(documentoBuilder.construirChurnRisco()).thenReturn("churn risco");
        when(documentoBuilder.construirFunilMedico()).thenReturn("funil médico");
        when(documentoBuilder.construirCancelamentos()).thenReturn("cancelamentos");

        service.syncAnalitico();

        verify(milvusIndexService, never()).indexarAnalitico(eq("resumo-analitico"), anyString());
        verify(milvusIndexService).indexarAnalitico("faturamento-mensal", "faturamento mensal");
        verify(milvusIndexService).indexarAnalitico("cancelamentos", "cancelamentos");
    }

    @Test
    void syncAnaliticoDeveContinuarQuandoFalhaAtualizarView() {
        doThrow(new RuntimeException("erro db")).when(jdbc).execute(anyString());
        when(documentoBuilder.construirAnalitico()).thenReturn("resumo analítico");

        service.syncAnalitico();

        verify(milvusIndexService).indexarAnalitico(eq("resumo-analitico"), anyString());
    }

    // ── sincronizar (POST /ia/etl/sync) ───────────────────────────────────────

    @Test
    void sincronizarComTipoDeveSincronizarSoAqueleTipoELiberarATrava() {
        travaLivre();
        when(jdbc.queryForList(contains("sgsm.medico"), eq(String.class))).thenReturn(List.of());

        Map<String, Object> resultado = service.sincronizar("MEDICO");

        assertThat(resultado.get("tipo")).isEqualTo("MEDICO");
        verify(travaRedis).liberar(EtlSyncService.CHAVE_TRAVA, "token-1");
    }

    @Test
    void sincronizarSemTipoDeveSincronizarTodos() {
        travaLivre();
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        Map<String, Object> resultado = service.sincronizar(null);

        assertThat(resultado).containsEntry("total", 0).containsEntry("erros", 0);
    }

    // Outra réplica (ou outra chamada) já detém a trava no Redis
    @Test
    void sincronizarDeveRecusarComOperacaoEmAndamentoQuandoATravaEstaOcupada() {
        when(travaRedis.adquirir(EtlSyncService.CHAVE_TRAVA, EtlSyncService.TTL_TRAVA)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sincronizar("PACIENTE")).isInstanceOf(OperacaoEmAndamentoException.class);
        verifyNoInteractions(jdbc);
        verify(travaRedis, never()).liberar(anyString(), anyString());
    }

    @Test
    void sincronizarDeveLiberarATravaMesmoQuandoFalha() {
        travaLivre();
        when(jdbc.queryForList(contains("sgsm.paciente"), eq(String.class))).thenThrow(new RuntimeException("db fora"));

        assertThatThrownBy(() -> service.sincronizar("PACIENTE")).isInstanceOf(RuntimeException.class);

        verify(travaRedis).liberar(EtlSyncService.CHAVE_TRAVA, "token-1");
    }
}

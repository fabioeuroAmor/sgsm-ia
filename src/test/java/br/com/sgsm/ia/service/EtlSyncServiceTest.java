package br.com.sgsm.ia.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    private EtlSyncService service;

    @BeforeEach
    void setUp() {
        service = new EtlSyncService(jdbc, documentoBuilder, milvusIndexService);
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
    void syncAnaliticoDeveAtualizarViewEIndexarKpis() {
        when(documentoBuilder.construirAnalitico()).thenReturn("resumo analítico");

        service.syncAnalitico();

        verify(jdbc).execute("REFRESH MATERIALIZED VIEW crm.mv_resumo_executivo");
        verify(milvusIndexService).indexarAnalitico("resumo analítico");
    }

    @Test
    void syncAnaliticoDeveContinuarQuandoOcorrerFalha() {
        doThrow(new RuntimeException("erro db")).when(jdbc).execute(anyString());

        service.syncAnalitico();

        verifyNoInteractions(milvusIndexService);
    }
}

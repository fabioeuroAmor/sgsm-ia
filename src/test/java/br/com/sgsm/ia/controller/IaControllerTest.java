package br.com.sgsm.ia.controller;

import br.com.sgsm.ia.dto.ChatRequest;
import br.com.sgsm.ia.service.AssistenteMedicoService;
import br.com.sgsm.ia.service.EtlSyncService;
import br.com.sgsm.ia.service.KpiService;
import br.com.sgsm.ia.service.MilvusIndexService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IaControllerTest {

    @Mock
    private AssistenteMedicoService assistenteMedicoService;
    @Mock
    private MilvusIndexService milvusIndexService;
    @Mock
    private KpiService kpiService;
    @Mock
    private EtlSyncService etlSyncService;

    private IaController controller;

    @BeforeEach
    void setUp() {
        controller = new IaController(assistenteMedicoService, milvusIndexService, kpiService, etlSyncService);
    }

    @Test
    void deveResponderPerguntaDoChat() {
        when(assistenteMedicoService.responder("Qual o histórico do paciente?")).thenReturn("resposta gerada");

        var response = controller.chat(new ChatRequest("Qual o histórico do paciente?"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().resposta()).isEqualTo("resposta gerada");
    }

    @Test
    void deveFiltrarBuscaPorTipo() {
        var matchPaciente = new EmbeddingMatch<>(0.9, "id1", null,
                TextSegment.from("texto paciente", Metadata.from(Map.of("tipo", "PACIENTE", "referencia_id", "1"))));
        when(milvusIndexService.buscar("consulta", "PACIENTE")).thenReturn(List.of(matchPaciente));

        var response = controller.busca("consulta", "PACIENTE");

        assertThat(response.getBody().documentos()).hasSize(1);
        assertThat(response.getBody().documentos().get(0).tipo()).isEqualTo("PACIENTE");
        verify(milvusIndexService, never()).buscar(anyString());
    }

    @Test
    void deveRetornarTodosOsDocumentosQuandoTipoNaoInformado() {
        var matchPaciente = new EmbeddingMatch<>(0.9, "id1", null,
                TextSegment.from("texto paciente", Metadata.from(Map.of("tipo", "PACIENTE", "referencia_id", "1"))));
        when(milvusIndexService.buscar("consulta", null)).thenReturn(List.of(matchPaciente));

        var response = controller.busca("consulta", null);

        assertThat(response.getBody().documentos()).hasSize(1);
    }

    @Test
    void deveGerarResumoDePaciente() {
        UUID id = UUID.randomUUID();
        when(assistenteMedicoService.responder(anyString())).thenReturn("resumo do paciente");

        var response = controller.resumoPaciente(id);

        assertThat(response.getBody().resposta()).isEqualTo("resumo do paciente");
    }

    @Test
    void deveRetornarKpisConsolidados() {
        when(kpiService.consolidado()).thenReturn(Map.of("resumo", Map.of("total", 10)));

        var response = controller.kpis();

        assertThat(response.getBody()).containsKey("resumo");
    }

    @Test
    void deveSincronizarTipoEspecificoQuandoInformado() {
        when(etlSyncService.syncTipo("PACIENTE")).thenReturn(Map.of("total", 5, "erros", 0));

        var response = controller.etlSync("PACIENTE");

        assertThat(response.getBody().get("total")).isEqualTo(5);
        verify(etlSyncService, never()).syncTodos();
    }

    @Test
    void deveSincronizarTodosQuandoTipoNaoInformado() {
        when(etlSyncService.syncTodos()).thenReturn(Map.of("total", 20, "erros", 1));

        var response = controller.etlSync(null);

        assertThat(response.getBody().get("total")).isEqualTo(20);
        verify(etlSyncService, never()).syncTipo(anyString());
    }
}

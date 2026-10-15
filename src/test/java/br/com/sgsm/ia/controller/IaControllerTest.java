package br.com.sgsm.ia.controller;

import br.com.sgsm.ia.dto.ChatRequest;
import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.service.AssistenteMedicoService;
import br.com.sgsm.ia.service.EtlSyncService;
import br.com.sgsm.ia.service.KpiService;
import br.com.sgsm.ia.service.RagAutorizacaoService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IaControllerTest {

    @Mock
    private AssistenteMedicoService assistenteMedicoService;
    @Mock
    private RagAutorizacaoService ragAutorizacaoService;
    @Mock
    private KpiService kpiService;
    @Mock
    private EtlSyncService etlSyncService;
    @Mock
    private EscopoAcessoService escopo;

    private IaController controller;

    @BeforeEach
    void setUp() {
        controller = new IaController(assistenteMedicoService, ragAutorizacaoService, kpiService, etlSyncService, escopo);
    }

    @Test
    void deveResponderPerguntaDoChat() {
        when(assistenteMedicoService.responder("Qual o histórico do paciente?")).thenReturn("resposta gerada");

        var response = controller.chat(new ChatRequest("Qual o histórico do paciente?"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().resposta()).isEqualTo("resposta gerada");
    }

    @Test
    void buscaDeveUsarABuscaAutorizadaComOTipoInformado() {
        var matchPaciente = new EmbeddingMatch<>(0.9, "id1", null,
                TextSegment.from("texto paciente", Metadata.from(Map.of("tipo", "PACIENTE", "referencia_id", "1"))));
        when(ragAutorizacaoService.buscar("consulta", "PACIENTE")).thenReturn(List.of(matchPaciente));

        var response = controller.busca("consulta", "PACIENTE");

        assertThat(response.getBody().documentos()).hasSize(1);
        assertThat(response.getBody().documentos().get(0).tipo()).isEqualTo("PACIENTE");
        assertThat(response.getBody().documentos().get(0).referenciaId()).isEqualTo("1");
    }

    @Test
    void buscaSemTipoDeveRepassarTipoNulo() {
        when(ragAutorizacaoService.buscar("consulta", null)).thenReturn(List.of());

        var response = controller.busca("consulta", null);

        assertThat(response.getBody().documentos()).isEmpty();
        verify(ragAutorizacaoService).buscar("consulta", null);
    }

    @Test
    void deveGerarResumoDePacienteDentroDoEscopo() {
        UUID id = UUID.randomUUID();
        when(assistenteMedicoService.responder(anyString())).thenReturn("resumo do paciente");

        var response = controller.resumoPaciente(id);

        assertThat(response.getBody().resposta()).isEqualTo("resumo do paciente");
        verify(escopo).validarPacienteNoEscopo(id.toString());
    }

    @Test
    void naoDeveChamarOLlmParaPacienteForaDoEscopo() {
        UUID id = UUID.randomUUID();
        doThrow(new RecursoNaoEncontradoException("Paciente não encontrado: " + id))
                .when(escopo).validarPacienteNoEscopo(id.toString());

        assertThatThrownBy(() -> controller.resumoPaciente(id))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(assistenteMedicoService);
    }

    @Test
    void deveRetornarKpisConsolidados() {
        when(kpiService.consolidado()).thenReturn(Map.of("resumo", Map.of("total", 10)));

        var response = controller.kpis();

        assertThat(response.getBody()).containsKey("resumo");
    }

    @Test
    void etlSyncDeveDelegarParaASincronizacaoExclusiva() {
        when(etlSyncService.sincronizar("PACIENTE")).thenReturn(Map.of("total", 5, "erros", 0));

        var response = controller.etlSync("PACIENTE");

        assertThat(response.getBody().get("total")).isEqualTo(5);
        verify(etlSyncService).sincronizar("PACIENTE");
    }
}

package br.com.sgsm.ia.controller;

import br.com.sgsm.ia.dto.AtualizarStatusLeadRequest;
import br.com.sgsm.ia.dto.ContatoRequest;
import br.com.sgsm.ia.dto.LeadRequest;
import br.com.sgsm.ia.dto.NotaClinicaRequest;
import br.com.sgsm.ia.dto.TagRequest;
import br.com.sgsm.ia.service.CrmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrmControllerTest {

    @Mock
    private CrmService crmService;

    private CrmController controller;

    @BeforeEach
    void setUp() {
        controller = new CrmController(crmService);
    }

    // ── leads ─────────────────────────────────────────────────────────────────

    @Test
    void listarLeadsDeveRetornar200ComListaDeLeads() {
        var leads = List.of(Map.<String, Object>of("nome", "Ana", "status", "NOVO"));
        when(crmService.listarLeads(null, null)).thenReturn(leads);

        var response = controller.listarLeads(null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void listarLeadsComFiltrosDevePassarFiltrosAoService() {
        when(crmService.listarLeads("NOVO", "SITE")).thenReturn(List.of());

        controller.listarLeads("NOVO", "SITE");

        verify(crmService).listarLeads("NOVO", "SITE");
    }

    @Test
    void criarLeadDeveRetornar201ComIdDoLead() {
        when(crmService.criarLead(any(LeadRequest.class))).thenReturn(Map.of("id", "uuid-1", "status", "NOVO"));

        var req = new LeadRequest("Ana", "ana@email.com", null, null, "SITE", null);
        var response = controller.criarLead(req);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsKey("id");
    }

    @Test
    void atualizarStatusLeadDeveRetornar204() {
        doNothing().when(crmService).atualizarStatusLead(anyString(), any(AtualizarStatusLeadRequest.class));

        var req = new AtualizarStatusLeadRequest("CONTATADO", null);
        var response = controller.atualizarStatusLead("lead-uuid", req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(crmService).atualizarStatusLead("lead-uuid", req);
    }

    // ── tags ──────────────────────────────────────────────────────────────────

    @Test
    void listarTagsDeveRetornar200ComTags() {
        var tags = List.of(Map.<String, Object>of("tag", "hipertenso"));
        when(crmService.listarTags("paciente-uuid")).thenReturn(tags);

        var response = controller.listarTags("paciente-uuid");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void adicionarTagDeveRetornar201() {
        doNothing().when(crmService).adicionarTag(anyString(), any(TagRequest.class));

        var response = controller.adicionarTag("paciente-uuid", new TagRequest("diabético"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void removerTagDeveRetornar204() {
        doNothing().when(crmService).removerTag("tag-uuid");

        var response = controller.removerTag("tag-uuid");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(crmService).removerTag("tag-uuid");
    }

    // ── contatos ──────────────────────────────────────────────────────────────

    @Test
    void listarContatosDeveRetornar200() {
        when(crmService.listarContatos("paciente-uuid")).thenReturn(List.of());

        var response = controller.listarContatos("paciente-uuid");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void registrarContatoDeveRetornar201() {
        doNothing().when(crmService).registrarContato(anyString(), any(ContatoRequest.class));

        var req = new ContatoRequest("LIGACAO", "SAIDA", "Ligação de retorno", 60);
        var response = controller.registrarContato("paciente-uuid", req);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    // ── notas ─────────────────────────────────────────────────────────────────

    @Test
    void listarNotasDeveRetornar200() {
        when(crmService.listarNotas("paciente-uuid")).thenReturn(List.of());

        var response = controller.listarNotas("paciente-uuid");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void adicionarNotaDeveRetornar201() {
        doNothing().when(crmService).adicionarNota(anyString(), any(NotaClinicaRequest.class));

        var req = new NotaClinicaRequest("EVOLUCAO", "Paciente melhorou", null);
        var response = controller.adicionarNota("paciente-uuid", req);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    // ── paciente 360 ──────────────────────────────────────────────────────────

    @Test
    void paciente360DeveRetornar200ComDados() {
        var dados = Map.<String, Object>of("nome", "Ana", "ltv", 1500.0);
        when(crmService.paciente360("paciente-uuid")).thenReturn(dados);

        var response = controller.paciente360("paciente-uuid");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("nome", "Ana");
    }

    // ── churn ─────────────────────────────────────────────────────────────────

    @Test
    void churnDeveRetornar200ComListaDeRisco() {
        var lista = List.of(Map.<String, Object>of("nome", "Carlos", "dias_sem_consulta", 95));
        when(crmService.churnRisco()).thenReturn(lista);

        var response = controller.churn();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
    }
}

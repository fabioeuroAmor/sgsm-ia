package br.com.sgsm.ia.service;

import br.com.sgsm.ia.dto.AtualizarStatusLeadRequest;
import br.com.sgsm.ia.dto.ContatoRequest;
import br.com.sgsm.ia.dto.LeadRequest;
import br.com.sgsm.ia.dto.NotaClinicaRequest;
import br.com.sgsm.ia.dto.TagRequest;
import br.com.sgsm.ia.security.ContextoSeguranca;
import br.com.sgsm.ia.security.NotaClinicaCryptoService;
import br.com.sgsm.ia.service.DocumentoBuilder;
import br.com.sgsm.ia.service.MilvusIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrmServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    @Mock
    private ContextoSeguranca contexto;

    @Mock
    private DocumentoBuilder documentoBuilder;

    @Mock
    private MilvusIndexService milvusIndexService;

    private NotaClinicaCryptoService notaClinicaCryptoService;
    private CrmService crmService;

    @BeforeEach
    void setUp() {
        String chaveAes = Base64.getEncoder().encodeToString("chave-teste-de-32-bytes-exatos!!".getBytes());
        notaClinicaCryptoService = new NotaClinicaCryptoService(chaveAes);
        crmService = new CrmService(jdbc, contexto, documentoBuilder, milvusIndexService, notaClinicaCryptoService);
    }

    // ── listarLeads ───────────────────────────────────────────────────────────

    @Test
    void listarLeadsSemFiltrosDeveRetornarTodosOsLeads() {
        var esperado = List.of(Map.<String, Object>of("nome", "Ana Lima", "status", "NOVO"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarLeads(null, null);

        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void listarLeadsComFiltroStatusDeveIncluirStatusNaQuery() {
        var esperado = List.of(Map.<String, Object>of("status", "CONTATADO"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarLeads("CONTATADO", null);

        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void listarLeadsComFiltroOrigemDeveIncluirOrigemNaQuery() {
        var esperado = List.of(Map.<String, Object>of("origem", "SITE"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarLeads(null, "SITE");

        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void listarLeadsComAmbosFiltrosDeveRetornarListaFiltrada() {
        var esperado = List.of(Map.<String, Object>of("status", "QUALIFICADO", "origem", "INDICACAO"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarLeads("QUALIFICADO", "INDICACAO");

        assertThat(resultado).isEqualTo(esperado);
    }

    // ── criarLead ─────────────────────────────────────────────────────────────

    @Test
    void criarLeadDeveInserirERetornarIdEStatus() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new LeadRequest("Ana Lima", "ana@email.com", "(11) 9999-0000", "Consulta", "SITE", "obs");
        var resultado = crmService.criarLead(req);

        assertThat(resultado).containsKey("id");
        assertThat(resultado.get("status")).isEqualTo("NOVO");
    }

    @Test
    void criarLeadSemOrigemDeveUsarOutro() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new LeadRequest("João", "j@email.com", null, null, null, null);
        var resultado = crmService.criarLead(req);

        assertThat(resultado.get("status")).isEqualTo("NOVO");
    }

    @Test
    void criarLeadComOrigemBrancoDeveUsarOutro() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new LeadRequest("Beto", "b@email.com", null, null, "  ", null);
        var resultado = crmService.criarLead(req);

        assertThat(resultado).containsKey("id");
    }

    @Test
    void criarLeadDeveContinuarSemLancarExcecaoQuandoIndexacaoFalha() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        doThrow(new RuntimeException("falha milvus"))
                .when(milvusIndexService).upsert(eq("LEAD"), anyString(), any());

        var req = new LeadRequest("Ana", "ana@email.com", null, null, "SITE", null);
        assertThatCode(() -> crmService.criarLead(req)).doesNotThrowAnyException();
    }

    // ── atualizarStatusLead ───────────────────────────────────────────────────

    @Test
    void atualizarStatusLeadDeveExecutarUpdateNoBanco() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var req = new AtualizarStatusLeadRequest("CONVERTIDO", "Virou paciente");

        crmService.atualizarStatusLead("lead-uuid", req);

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    // ── tags ──────────────────────────────────────────────────────────────────

    @Test
    void listarTagsDeveRetornarTagsDoPaciente() {
        var esperado = List.of(Map.<String, Object>of("tag", "hipertenso"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarTags("paciente-uuid");

        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void adicionarTagDeveInserirNoBanco() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.adicionarTag("paciente-uuid", new TagRequest("  Diabético  "));

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void removerTagDeveExecutarDeleteNoBanco() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.removerTag("tag-uuid");

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void removerTagDeveReindexarPacienteQuandoTagExistir() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("paciente_id", "paciente-uuid")));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.removerTag("tag-uuid");

        verify(milvusIndexService).upsert(eq("PACIENTE"), eq("paciente-uuid"), any());
    }

    @Test
    void adicionarTagDeveContinuarSemLancarExcecaoQuandoReindexacaoFalha() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        doThrow(new RuntimeException("falha milvus"))
                .when(milvusIndexService).upsert(anyString(), anyString(), any());

        assertThatCode(() -> crmService.adicionarTag("paciente-uuid", new TagRequest("Hipertenso")))
                .doesNotThrowAnyException();
    }

    // ── contatos ──────────────────────────────────────────────────────────────

    @Test
    void listarContatosDeveRetornarHistoricoDoPaciente() {
        var esperado = List.of(Map.<String, Object>of("tipo", "LIGACAO", "descricao", "Ligação de retorno"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarContatos("paciente-uuid");

        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void registrarContatoDeveInserirNoBanco() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new ContatoRequest("LIGACAO", "SAIDA", "Retorno ao paciente", 120);
        crmService.registrarContato("paciente-uuid", req);

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    // ── notas clínicas ────────────────────────────────────────────────────────

    @Test
    void listarNotasDeveRetornarNotasDoPaciente() {
        var esperado = List.of(Map.<String, Object>of("conteudo", "Paciente relata melhora"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarNotas("paciente-uuid");

        assertThat(resultado).isEqualTo(esperado);
    }

    @Test
    void adicionarNotaComRoleMedicoDeveUsarReferenciaIdComoMedicoId() {
        when(contexto.isMedico()).thenReturn(true);
        when(contexto.getReferenciaIdStr()).thenReturn("medico-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new NotaClinicaRequest("EVOLUCAO", "Paciente estável", null);
        crmService.adicionarNota("paciente-uuid", req);

        verify(contexto).getReferenciaIdStr();
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void adicionarNotaSemRoleMedicoDeveDeixarMedicoIdNulo() {
        when(contexto.isMedico()).thenReturn(false);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new NotaClinicaRequest("OBSERVACAO", "Observação geral", null);
        crmService.adicionarNota("paciente-uuid", req);

        verify(contexto, never()).getReferenciaIdStr();
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void adicionarNotaComAgendamentoIdDeveUsarOId() {
        when(contexto.isMedico()).thenReturn(true);
        when(contexto.getReferenciaIdStr()).thenReturn("medico-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new NotaClinicaRequest("ANAMNESE", "Anamnese inicial", "agend-uuid");
        crmService.adicionarNota("paciente-uuid", req);

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void adicionarNotaComAgendamentoIdBrancoDeveUsarNulo() {
        when(contexto.isMedico()).thenReturn(false);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new NotaClinicaRequest("RETORNO", "Retorno ok", "   ");
        crmService.adicionarNota("paciente-uuid", req);

        verify(jdbc).update(anyString(), any(Object[].class));
    }

    // ── paciente 360 ──────────────────────────────────────────────────────────

    @Test
    void paciente360DeveRetornarDadosQuandoViewTemResultado() {
        var dados = Map.<String, Object>of("nome", "Ana", "ltv", 1200.0);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(dados));

        var resultado = crmService.paciente360("paciente-uuid");

        assertThat(resultado).containsEntry("nome", "Ana");
    }

    @Test
    void paciente360DeveRetornarMapaVazioQuandoViewNaoTiverResultado() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        var resultado = crmService.paciente360("paciente-uuid");

        assertThat(resultado).isEmpty();
    }

    @Test
    void paciente360DeveRetornarMapaVazioQuandoOcorrerExcecao() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(new RuntimeException("DB error"));

        var resultado = crmService.paciente360("paciente-uuid");

        assertThat(resultado).isEmpty();
    }

    // ── churn ─────────────────────────────────────────────────────────────────

    @Test
    void churnRiscoDeveRetornarListaDePacientesEmRisco() {
        var esperado = List.of(Map.<String, Object>of("nome", "Carlos", "dias_sem_consulta", 120));
        when(jdbc.queryForList(anyString())).thenReturn(esperado);

        var resultado = crmService.churnRisco();

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0)).containsEntry("nome", "Carlos");
    }
}

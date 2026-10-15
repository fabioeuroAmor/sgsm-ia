package br.com.sgsm.ia.service;

import br.com.sgsm.ia.dto.AtualizarStatusLeadRequest;
import br.com.sgsm.ia.dto.ContatoRequest;
import br.com.sgsm.ia.dto.LeadRequest;
import br.com.sgsm.ia.dto.NotaClinicaRequest;
import br.com.sgsm.ia.dto.TagRequest;
import br.com.sgsm.ia.exception.AcessoNegadoException;
import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.security.ContextoSeguranca;
import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.security.EscopoAcessoService.Subconsulta;
import br.com.sgsm.ia.security.NotaClinicaCryptoService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CrmServiceTest {

    private static final Subconsulta ESTABELECIMENTOS = new Subconsulta("SUBQ_ESTAB", List.of("ref-uuid"));
    private static final Subconsulta AGENDAMENTOS = new Subconsulta("PRED_AGEND", List.of("ref-uuid"));

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ContextoSeguranca contexto;
    @Mock
    private DocumentoBuilder documentoBuilder;
    @Mock
    private MilvusIndexService milvusIndexService;
    @Mock
    private EscopoAcessoService escopo;
    @Mock
    private KpiService kpiService;
    @Mock
    private AuditoriaService auditoriaService;

    private NotaClinicaCryptoService notaClinicaCryptoService;
    private CrmService crmService;

    @BeforeEach
    void setUp() {
        String chaveAes = Base64.getEncoder().encodeToString("chave-teste-de-32-bytes-exatos!!".getBytes());
        notaClinicaCryptoService = new NotaClinicaCryptoService(chaveAes);
        crmService = new CrmService(jdbc, contexto, documentoBuilder, milvusIndexService, notaClinicaCryptoService,
                escopo, kpiService, auditoriaService);
    }

    private void comoDesenvolvedor() {
        when(escopo.estabelecimentos()).thenReturn(null);
    }

    private void comoStaffDeClinica() {
        when(escopo.estabelecimentos()).thenReturn(ESTABELECIMENTOS);
    }

    private void pacienteForaDoEscopo(String pacienteId) {
        doThrow(new RecursoNaoEncontradoException("Paciente não encontrado: " + pacienteId))
                .when(escopo).validarPacienteNoEscopo(pacienteId);
    }

    // ── listarLeads ───────────────────────────────────────────────────────────

    @Test
    void listarLeadsComoDesenvolvedorNaoDeveFiltrarPorEstabelecimento() {
        comoDesenvolvedor();
        var esperado = List.of(Map.<String, Object>of("nome", "Ana Lima", "status", "NOVO"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarLeads(null, null);

        assertThat(resultado).isEqualTo(esperado);
        verify(jdbc).queryForList(argThat((String sql) -> !sql.contains("estabelecimento_id IN")), any(Object[].class));
    }

    @Test
    void listarLeadsComoStaffDeveFiltrarPelosEstabelecimentosPermitidos() {
        comoStaffDeClinica();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        crmService.listarLeads("CONTATADO", "SITE");

        verify(jdbc).queryForList(
                argThat((String sql) -> sql.contains("estabelecimento_id IN (SUBQ_ESTAB)")
                        && sql.indexOf("SUBQ_ESTAB") < sql.indexOf("status = ?")),
                eq("ref-uuid"), eq("CONTATADO"), eq("SITE"));
    }

    @Test
    void listarLeadsComFiltroOrigemDeveIncluirOrigemNaQuery() {
        comoDesenvolvedor();
        var esperado = List.of(Map.<String, Object>of("origem", "SITE"));
        when(jdbc.queryForList(contains("origem = ?"), any(Object[].class))).thenReturn(esperado);

        assertThat(crmService.listarLeads(null, "SITE")).isEqualTo(esperado);
    }

    // ── criarLead ─────────────────────────────────────────────────────────────

    @Test
    void criarLeadComEstabelecimentoDeveValidarEGravarNoLead() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new LeadRequest("Ana Lima", "ana@email.com", "(11) 9999-0000", "Consulta", "SITE", "obs", "estab-1");
        var resultado = crmService.criarLead(req);

        assertThat(resultado).containsKey("id").containsEntry("status", "NOVO");
        verify(escopo).validarEstabelecimentoPermitido("estab-1");
        verify(jdbc).update(contains("estabelecimento_id"), any(), any(), any(), any(), any(), any(), any(), any(), eq("estab-1"));
    }

    @Test
    void criarLeadComoStaffSemEstabelecimentoDeveSerNegado() {
        comoStaffDeClinica();
        doThrow(new AcessoNegadoException("Acesso negado ao estabelecimento: null"))
                .when(escopo).validarEstabelecimentoPermitido(null);

        var req = new LeadRequest("João", "j@email.com", null, null, null, null, null);

        assertThatThrownBy(() -> crmService.criarLead(req)).isInstanceOf(AcessoNegadoException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void criarLeadComoDesenvolvedorPodeOmitirEstabelecimento() {
        comoDesenvolvedor();
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var req = new LeadRequest("Beto", "b@email.com", null, null, "  ", null, null);
        var resultado = crmService.criarLead(req);

        assertThat(resultado).containsKey("id");
        verify(escopo, never()).validarEstabelecimentoPermitido(any());
    }

    @Test
    void criarLeadDeveContinuarSemLancarExcecaoQuandoIndexacaoFalha() {
        comoDesenvolvedor();
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        doThrow(new RuntimeException("falha milvus"))
                .when(milvusIndexService).upsert(eq("LEAD"), anyString(), any());

        var req = new LeadRequest("Ana", "ana@email.com", null, null, "SITE", null, null);
        assertThatCode(() -> crmService.criarLead(req)).doesNotThrowAnyException();
    }

    // ── atualizarStatusLead ───────────────────────────────────────────────────

    @Test
    void atualizarStatusLeadComoDesenvolvedorDeveExecutarUpdate() {
        comoDesenvolvedor();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.atualizarStatusLead("lead-uuid", new AtualizarStatusLeadRequest("CONVERTIDO", "Virou paciente"));

        verify(jdbc).update(contains("UPDATE crm.lead"), any(Object[].class));
    }

    @Test
    void atualizarStatusLeadDeOutraClinicaDeveRetornar404SemAlterar() {
        comoStaffDeClinica();
        when(escopo.existe(contains("FROM crm.lead"), any(Object[].class))).thenReturn(false);

        assertThatThrownBy(() -> crmService.atualizarStatusLead("lead-uuid", new AtualizarStatusLeadRequest("PERDIDO", null)))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void atualizarStatusLeadDaPropriaClinicaDeveExecutarUpdate() {
        comoStaffDeClinica();
        when(escopo.existe(contains("FROM crm.lead"), any(Object[].class))).thenReturn(true);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.atualizarStatusLead("lead-uuid", new AtualizarStatusLeadRequest("CONTATADO", null));

        verify(jdbc).update(contains("UPDATE crm.lead"), any(Object[].class));
    }

    // ── tags ──────────────────────────────────────────────────────────────────

    @Test
    void listarTagsDeveRetornarTagsDoPacienteNoEscopo() {
        var esperado = List.of(Map.<String, Object>of("tag", "hipertenso"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        var resultado = crmService.listarTags("paciente-uuid");

        assertThat(resultado).isEqualTo(esperado);
        verify(escopo).validarPacienteNoEscopo("paciente-uuid");
    }

    @Test
    void listarTagsDePacienteForaDoEscopoNaoDeveConsultar() {
        pacienteForaDoEscopo("paciente-uuid");

        assertThatThrownBy(() -> crmService.listarTags("paciente-uuid")).isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void adicionarTagDeveInserirNoBanco() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.adicionarTag("paciente-uuid", new TagRequest("  Diabético  "));

        verify(escopo).validarPacienteNoEscopo("paciente-uuid");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void adicionarTagEmPacienteForaDoEscopoNaoDeveInserir() {
        pacienteForaDoEscopo("paciente-uuid");

        assertThatThrownBy(() -> crmService.adicionarTag("paciente-uuid", new TagRequest("x")))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(jdbc);
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

    @Test
    void removerTagNoEscopoDeveApagarEReindexarPaciente() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("paciente_id", "paciente-uuid")));
        when(escopo.pacienteNoEscopo("paciente-uuid")).thenReturn(true);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.removerTag("tag-uuid");

        verify(jdbc).update(contains("DELETE FROM crm.tag_paciente"), any(Object[].class));
        verify(milvusIndexService).upsert(eq("PACIENTE"), eq("paciente-uuid"), any());
    }

    @Test
    void removerTagDePacienteForaDoEscopoDeveRetornar404SemApagar() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("paciente_id", "paciente-de-outra-clinica")));
        when(escopo.pacienteNoEscopo("paciente-de-outra-clinica")).thenReturn(false);

        assertThatThrownBy(() -> crmService.removerTag("tag-uuid")).isInstanceOf(RecursoNaoEncontradoException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void removerTagInexistenteDeveRetornar404() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertThatThrownBy(() -> crmService.removerTag("tag-uuid")).isInstanceOf(RecursoNaoEncontradoException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // ── contatos ──────────────────────────────────────────────────────────────

    @Test
    void listarContatosDeveRetornarHistoricoDoPacienteNoEscopo() {
        var esperado = List.of(Map.<String, Object>of("tipo", "LIGACAO", "descricao", "Ligação de retorno"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(esperado);

        assertThat(crmService.listarContatos("paciente-uuid")).isEqualTo(esperado);
        verify(escopo).validarPacienteNoEscopo("paciente-uuid");
    }

    @Test
    void registrarContatoDeveInserirNoBanco() {
        when(contexto.getUsuarioId()).thenReturn("usuario-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.registrarContato("paciente-uuid", new ContatoRequest("LIGACAO", "SAIDA", "Retorno ao paciente", 120));

        verify(escopo).validarPacienteNoEscopo("paciente-uuid");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void registrarContatoEmPacienteForaDoEscopoNaoDeveInserir() {
        pacienteForaDoEscopo("paciente-uuid");

        assertThatThrownBy(() -> crmService.registrarContato("paciente-uuid",
                new ContatoRequest("LIGACAO", "SAIDA", "x", 1))).isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(jdbc);
    }

    // ── notas clínicas ────────────────────────────────────────────────────────

    @Test
    void listarNotasDeveDecifrarConteudoEAuditarLeitura() {
        String cifrado = notaClinicaCryptoService.encrypt("Paciente relata melhora");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.<String, Object>of("conteudo", cifrado)));

        var resultado = crmService.listarNotas("paciente-uuid");

        assertThat(resultado.get(0)).containsEntry("conteudo", "Paciente relata melhora");
        verify(escopo).validarPacienteAtendidoPeloMedico("paciente-uuid");
        verify(auditoriaService).registrarLeitura("NOTA_CLINICA", "paciente-uuid");
    }

    @Test
    void listarNotasDePacienteNaoAtendidoNaoDeveConsultarNemDecifrar() {
        doThrow(new RecursoNaoEncontradoException("Paciente não encontrado"))
                .when(escopo).validarPacienteAtendidoPeloMedico("paciente-uuid");

        assertThatThrownBy(() -> crmService.listarNotas("paciente-uuid")).isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(jdbc, auditoriaService);
    }

    @Test
    void adicionarNotaComoMedicoDeveUsarReferenciaIdComoMedicoId() {
        when(contexto.isMedico()).thenReturn(true);
        when(contexto.getReferenciaIdStr()).thenReturn("medico-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.adicionarNota("paciente-uuid", new NotaClinicaRequest("EVOLUCAO", "Paciente estável", null));

        verify(escopo).validarPacienteAtendidoPeloMedico("paciente-uuid");
        verify(jdbc).update(anyString(), isNull(), eq("paciente-uuid"), eq("medico-uuid"), eq("EVOLUCAO"), anyString());
    }

    @Test
    void adicionarNotaSemSerMedicoDeveSerNegado() {
        when(contexto.isMedico()).thenReturn(false);

        assertThatThrownBy(() -> crmService.adicionarNota("paciente-uuid",
                new NotaClinicaRequest("OBSERVACAO", "Observação geral", null))).isInstanceOf(AcessoNegadoException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void adicionarNotaEmPacienteNaoAtendidoPeloMedicoNaoDeveInserir() {
        when(contexto.isMedico()).thenReturn(true);
        doThrow(new RecursoNaoEncontradoException("Paciente não encontrado"))
                .when(escopo).validarPacienteAtendidoPeloMedico("paciente-uuid");

        assertThatThrownBy(() -> crmService.adicionarNota("paciente-uuid",
                new NotaClinicaRequest("EVOLUCAO", "x", null))).isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void adicionarNotaComAgendamentoIdDeveUsarOId() {
        when(contexto.isMedico()).thenReturn(true);
        when(contexto.getReferenciaIdStr()).thenReturn("medico-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.adicionarNota("paciente-uuid", new NotaClinicaRequest("ANAMNESE", "Anamnese inicial", "agend-uuid"));

        verify(jdbc).update(anyString(), eq("agend-uuid"), eq("paciente-uuid"), eq("medico-uuid"), eq("ANAMNESE"), anyString());
    }

    @Test
    void adicionarNotaComAgendamentoIdBrancoDeveUsarNulo() {
        when(contexto.isMedico()).thenReturn(true);
        when(contexto.getReferenciaIdStr()).thenReturn("medico-uuid");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        crmService.adicionarNota("paciente-uuid", new NotaClinicaRequest("RETORNO", "Retorno ok", "   "));

        verify(jdbc).update(anyString(), isNull(), eq("paciente-uuid"), eq("medico-uuid"), eq("RETORNO"), anyString());
    }

    // ── paciente 360 ──────────────────────────────────────────────────────────

    @Test
    void paciente360ComoDesenvolvedorDeveUsarViewGlobal() {
        when(escopo.predicadoAgendamento("a")).thenReturn(null);
        var dados = Map.<String, Object>of("nome", "Ana", "ltv_total", 1200.0);
        when(jdbc.queryForList(contains("crm.v_paciente_360"), any(Object[].class))).thenReturn(List.of(dados));

        assertThat(crmService.paciente360("paciente-uuid")).containsEntry("nome", "Ana");
        verify(auditoriaService).registrarLeitura("PACIENTE_360", "paciente-uuid");
    }

    @Test
    void paciente360ComoStaffDeveCalcularSoComAgendamentosDoEscopo() {
        when(escopo.predicadoAgendamento("a")).thenReturn(AGENDAMENTOS);
        when(jdbc.queryForList(contains("PRED_AGEND"), eq("ref-uuid"), eq("paciente-uuid")))
                .thenReturn(List.of(Map.of("nome", "Ana", "ltv_total", 300.0)));

        var resultado = crmService.paciente360("paciente-uuid");

        assertThat(resultado).containsEntry("ltv_total", 300.0);
        verify(jdbc, never()).queryForList(contains("crm.v_paciente_360"), any(Object[].class));
    }

    @Test
    void paciente360ForaDoEscopoDeveRetornar404SemAuditarNemConsultar() {
        pacienteForaDoEscopo("paciente-uuid");

        assertThatThrownBy(() -> crmService.paciente360("paciente-uuid")).isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(jdbc, auditoriaService);
    }

    @Test
    void paciente360DeveRetornarMapaVazioQuandoNaoHouverResultado() {
        when(escopo.predicadoAgendamento("a")).thenReturn(null);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        assertThat(crmService.paciente360("paciente-uuid")).isEmpty();
    }

    @Test
    void paciente360DeveRetornarMapaVazioQuandoOcorrerExcecao() {
        when(escopo.predicadoAgendamento("a")).thenReturn(null);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(new RuntimeException("DB error"));

        assertThat(crmService.paciente360("paciente-uuid")).isEmpty();
    }

    // ── churn ─────────────────────────────────────────────────────────────────

    @Test
    void churnRiscoDeveDelegarParaOKpiEscopadoDoUsuario() {
        var esperado = List.of(Map.<String, Object>of("nome", "Carlos"));
        when(kpiService.churnRiscoDoUsuario(100)).thenReturn(esperado);

        assertThat(crmService.churnRisco()).isEqualTo(esperado);
    }
}

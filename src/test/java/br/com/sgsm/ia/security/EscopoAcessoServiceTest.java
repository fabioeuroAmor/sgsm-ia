package br.com.sgsm.ia.security;

import br.com.sgsm.ia.exception.AcessoNegadoException;
import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.security.EscopoAcessoService.Subconsulta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscopoAcessoServiceTest {

    private static final String REF = "11111111-1111-1111-1111-111111111111";

    @Mock
    private ContextoSeguranca contexto;
    @Mock
    private JdbcTemplate jdbc;

    private EscopoAcessoService escopo;

    @BeforeEach
    void setUp() {
        escopo = new EscopoAcessoService(contexto, jdbc);
    }

    private void comoMedico() {
        lenient().when(contexto.getReferenciaIdStr()).thenReturn(REF);
        lenient().when(contexto.isMedico()).thenReturn(true);
    }

    private void comoFuncionario() {
        lenient().when(contexto.getReferenciaIdStr()).thenReturn(REF);
        lenient().when(contexto.isFuncionario()).thenReturn(true);
    }

    private void comoAdmin() {
        lenient().when(contexto.getReferenciaIdStr()).thenReturn(REF);
        lenient().when(contexto.isAdminEstabelecimento()).thenReturn(true);
    }

    private void comoPaciente() {
        lenient().when(contexto.getReferenciaIdStr()).thenReturn(REF);
    }

    // ── estabelecimentos() ────────────────────────────────────────────────────

    @Test
    void desenvolvedorDeveSerIrrestrito() {
        when(contexto.isDesenvolvedor()).thenReturn(true);

        assertThat(escopo.estabelecimentos()).isNull();
        assertThat(escopo.medicos()).isNull();
        assertThat(escopo.predicadoAgendamento("a")).isNull();
    }

    @Test
    void medicoDeveSerEscopadoPelosVinculosAtivos() {
        comoMedico();

        Subconsulta sub = escopo.estabelecimentos();

        assertThat(sub.sql()).contains("sgsm.medico_estabelecimento").contains("medico_id = ?").contains("ativo = true");
        assertThat(sub.params()).containsExactly(REF);
    }

    @Test
    void funcionarioDeveSerEscopadoPeloProprioEstabelecimento() {
        comoFuncionario();

        Subconsulta sub = escopo.estabelecimentos();

        assertThat(sub.sql()).contains("sgsm.funcionario").contains("id = ?");
        assertThat(sub.params()).containsExactly(REF);
    }

    @Test
    void adminDeveSerEscopadoPelosEstabelecimentosAdministrados() {
        comoAdmin();

        Subconsulta sub = escopo.estabelecimentos();

        assertThat(sub.sql()).contains("sgsm.administrador_estabelecimento").contains("administrador_id = ?");
        assertThat(sub.params()).containsExactly(REF);
    }

    // Diferença proposital em relação ao sgsm: papel não mapeado NUNCA vira irrestrito
    @Test
    void pacienteNaoDeveSerIrrestritoDeveReceberSubconsultaVazia() {
        comoPaciente();

        Subconsulta sub = escopo.estabelecimentos();

        assertThat(sub).isNotNull();
        assertThat(sub.sql()).contains("WHERE false");
        assertThat(sub.params()).isEmpty();
    }

    @Test
    void usuarioSemReferenciaIdDeveReceberSubconsultaVazia() {
        when(contexto.getReferenciaIdStr()).thenReturn(null);

        Subconsulta sub = escopo.estabelecimentos();

        assertThat(sub.sql()).contains("WHERE false");
    }

    // ── medicos() / predicadoAgendamento() ────────────────────────────────────

    @Test
    void medicosDeveEncadearSubconsultaDeEstabelecimentos() {
        comoAdmin();

        Subconsulta sub = escopo.medicos();

        assertThat(sub.sql())
                .startsWith("SELECT me.medico_id FROM sgsm.medico_estabelecimento me")
                .contains("sgsm.administrador_estabelecimento");
        assertThat(sub.params()).containsExactly(REF);
    }

    @Test
    void predicadoAgendamentoDeveCobrirPresencialEDomiciliar() {
        comoFuncionario();

        Subconsulta sub = escopo.predicadoAgendamento("ag");

        assertThat(sub.sql())
                .contains("ag.estabelecimento_id IN (")
                .contains("ag.estabelecimento_id IS NULL AND ag.medico_id IN (");
        // um parâmetro para a subconsulta de estabelecimentos e outro para a de médicos
        assertThat(sub.params()).containsExactly(REF, REF);
    }

    @Test
    void comParametrosDeveColocarOsParametrosInformadosAntesDosDaSubconsulta() {
        var sub = new Subconsulta("X", java.util.List.of("b"));

        assertThat(sub.comParametros("a")).containsExactly("a", "b");
        assertThat(sub.comParametros((Object) null)).containsExactly(null, "b");
    }

    // ── pacienteNoEscopo ──────────────────────────────────────────────────────

    @Test
    void pacienteNoEscopoParaDesenvolvedorNaoConsultaOBanco() {
        when(contexto.isDesenvolvedor()).thenReturn(true);

        assertThat(escopo.pacienteNoEscopo("p-1")).isTrue();
        verifyNoInteractions(jdbc);
    }

    @Test
    void pacienteNoEscopoDeveVerificarAgendamentoComMedicoDasClinicas() {
        comoAdmin();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(true);

        assertThat(escopo.pacienteNoEscopo("p-1")).isTrue();
        verify(jdbc).queryForObject(
                argThat((String sql) -> sql.contains("a.paciente_id = ?") && sql.contains("a.medico_id IN (")),
                eq(Boolean.class), eq("p-1"), eq(REF));
    }

    @Test
    void validarPacienteForaDoEscopoDeveLancar404() {
        comoFuncionario();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);

        assertThatThrownBy(() -> escopo.validarPacienteNoEscopo("p-1"))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void validarPacienteNoEscopoNaoDeveLancar() {
        when(contexto.isDesenvolvedor()).thenReturn(true);

        assertThatCode(() -> escopo.validarPacienteNoEscopo("p-1")).doesNotThrowAnyException();
    }

    @Test
    void retornoNuloDoBancoDeveSerTratadoComoNegado() {
        comoMedico();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(null);

        assertThat(escopo.pacienteNoEscopo("p-1")).isFalse();
    }

    // ── pacienteAtendidoPeloMedico (notas clínicas) ───────────────────────────

    @Test
    void desenvolvedorPodeLerNotasDeQualquerPaciente() {
        when(contexto.isDesenvolvedor()).thenReturn(true);

        assertThat(escopo.pacienteAtendidoPeloMedico("p-1")).isTrue();
        verifyNoInteractions(jdbc);
    }

    @Test
    void medicoSoLeNotasDePacienteQueEleAtendeu() {
        comoMedico();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("p-1"), eq(REF))).thenReturn(true);

        assertThat(escopo.pacienteAtendidoPeloMedico("p-1")).isTrue();
    }

    @Test
    void funcionarioEAdminNaoLeemNotasClinicas() {
        comoAdmin();

        assertThat(escopo.pacienteAtendidoPeloMedico("p-1")).isFalse();
        assertThatThrownBy(() -> escopo.validarPacienteAtendidoPeloMedico("p-1"))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void medicoSemReferenciaIdNaoLeNotas() {
        when(contexto.isMedico()).thenReturn(true);
        when(contexto.getReferenciaIdStr()).thenReturn(null);

        assertThat(escopo.pacienteAtendidoPeloMedico("p-1")).isFalse();
    }

    @Test
    void validarPacienteAtendidoPeloMedicoNaoDeveLancarQuandoAtendeu() {
        when(contexto.isDesenvolvedor()).thenReturn(true);

        assertThatCode(() -> escopo.validarPacienteAtendidoPeloMedico("p-1")).doesNotThrowAnyException();
    }

    // ── estabelecimentoPermitido ──────────────────────────────────────────────

    @Test
    void desenvolvedorPodeUsarQualquerEstabelecimento() {
        when(contexto.isDesenvolvedor()).thenReturn(true);

        assertThat(escopo.estabelecimentoPermitido(null)).isTrue();
        assertThatCode(() -> escopo.validarEstabelecimentoPermitido("e-1")).doesNotThrowAnyException();
    }

    @Test
    void estabelecimentoNuloDeveSerNegadoParaStaff() {
        comoFuncionario();

        assertThat(escopo.estabelecimentoPermitido(null)).isFalse();
        verifyNoInteractions(jdbc);
    }

    @Test
    void estabelecimentoForaDoEscopoDeveLancar403() {
        comoAdmin();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("e-9"), eq(REF))).thenReturn(false);

        assertThatThrownBy(() -> escopo.validarEstabelecimentoPermitido("e-9"))
                .isInstanceOf(AcessoNegadoException.class);
    }

    @Test
    void estabelecimentoDoEscopoDeveSerPermitido() {
        comoAdmin();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("e-1"), eq(REF))).thenReturn(true);

        assertThat(escopo.estabelecimentoPermitido("e-1")).isTrue();
    }
}

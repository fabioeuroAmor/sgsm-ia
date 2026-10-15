package br.com.sgsm.ia.service;

import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.security.EscopoAcessoService.Subconsulta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KpiServiceTest {

    // Subconsultas falsas, só para verificar que entram no SQL e nos parâmetros
    private static final Subconsulta AGENDAMENTOS = new Subconsulta("PRED_AGENDAMENTO", List.of("p-agend"));
    private static final Subconsulta MEDICOS = new Subconsulta("SUBQ_MEDICOS", List.of("p-medico"));

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private EscopoAcessoService escopo;

    private KpiService kpiService;

    @BeforeEach
    void setUp() {
        kpiService = new KpiService(jdbc, escopo);
    }

    @Test
    void desenvolvedorDeveReceberKpisGlobaisDasViews() {
        when(escopo.predicadoAgendamento("a")).thenReturn(null);
        when(jdbc.queryForList(contains("mv_resumo_executivo"))).thenReturn(List.of(Map.of("totalPacientes", 10)));
        when(jdbc.queryForList(contains("v_faturamento_mensal"))).thenReturn(List.of(Map.of("mes", "2026-07")));
        when(jdbc.queryForList(contains("v_ocupacao_agenda"))).thenReturn(List.of(Map.of("data", "2026-07-18")));
        when(jdbc.queryForList(contains("v_alto_valor"))).thenReturn(List.of(Map.of("paciente", "Fulano")));
        when(jdbc.queryForList(contains("v_churn_risco"))).thenReturn(List.of(Map.of("paciente", "Ciclano")));
        when(jdbc.queryForList(contains("v_funil_medico"))).thenReturn(List.of(Map.of("medico", "Dr. Fulano")));
        when(jdbc.queryForList(contains("v_cancelamentos"))).thenReturn(List.of(Map.of("motivo", "sem motivo")));

        Map<String, Object> resultado = kpiService.consolidado();

        assertThat(resultado).containsKeys(
                "resumo", "faturamentoMensal", "ocupacaoAgenda",
                "pacientesAltoValor", "churnRisco", "funilMedico", "cancelamentos");
        assertThat(resultado.get("resumo")).isEqualTo(Map.of("totalPacientes", 10));
    }

    @Test
    void demaisPapeisDevemReceberKpisRecalculadosSoNoEscopo() {
        when(escopo.predicadoAgendamento("a")).thenReturn(AGENDAMENTOS);
        when(escopo.medicos()).thenReturn(MEDICOS);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("k", 1)));

        Map<String, Object> resultado = kpiService.consolidado();

        assertThat(resultado).containsKeys(
                "resumo", "faturamentoMensal", "ocupacaoAgenda",
                "pacientesAltoValor", "churnRisco", "funilMedico", "cancelamentos");
        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).queryForList(sqlCaptor.capture(), any(Object[].class));
        // Nenhuma view global é lida e toda query carrega o predicado de escopo
        assertThat(sqlCaptor.getAllValues())
                .hasSize(7)
                .allSatisfy(sql -> assertThat(sql).contains("PRED_AGENDAMENTO").doesNotContain("crm.v_", "crm.mv_"));
        verify(jdbc, never()).queryForList(anyString());
    }

    @Test
    void parametrosDevemSeguirAOrdemDasSubconsultasNoTextoDaQuery() {
        when(escopo.predicadoAgendamento("a")).thenReturn(AGENDAMENTOS);
        when(escopo.medicos()).thenReturn(MEDICOS);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("k", 1)));

        kpiService.consolidado();

        // resumo: subconsulta de médicos aparece no SELECT, antes do WHERE com o predicado
        verify(jdbc).queryForList(contains("total_pacientes"), eq("p-medico"), eq("p-agend"));
        // ocupação/funil: predicado no JOIN, médicos no WHERE
        verify(jdbc).queryForList(contains("slots_totais"), eq("p-agend"), eq("p-medico"));
        verify(jdbc).queryForList(contains("ORDER BY receita_total DESC"), eq("p-agend"), eq("p-medico"));
    }

    @Test
    void churnDoUsuarioDeveUsarViewGlobalParaDesenvolvedor() {
        when(escopo.predicadoAgendamento("a")).thenReturn(null);
        when(jdbc.queryForList("SELECT * FROM crm.v_churn_risco LIMIT 100")).thenReturn(List.of(Map.of("nome", "Carlos")));

        assertThat(kpiService.churnRiscoDoUsuario(100)).hasSize(1);
    }

    @Test
    void churnDoUsuarioDeveSerEscopadoParaDemaisPapeis() {
        when(escopo.predicadoAgendamento("a")).thenReturn(AGENDAMENTOS);
        when(jdbc.queryForList(contains("PRED_AGENDAMENTO"), eq("p-agend"))).thenReturn(List.of(Map.of("nome", "Ana")));

        var resultado = kpiService.churnRiscoDoUsuario(100);

        assertThat(resultado).hasSize(1);
        verify(jdbc).queryForList(contains("LIMIT 100"), eq("p-agend"));
    }

    @Test
    void resumoDoUsuarioDeveSerGlobalParaDesenvolvedorEEscopadoParaOsDemais() {
        when(escopo.predicadoAgendamento("a")).thenReturn(null);
        when(jdbc.queryForList(contains("mv_resumo_executivo"))).thenReturn(List.of(Map.of("receita_total", 9)));
        assertThat(kpiService.resumoDoUsuario()).containsEntry("receita_total", 9);

        when(escopo.predicadoAgendamento("a")).thenReturn(AGENDAMENTOS);
        when(escopo.medicos()).thenReturn(MEDICOS);
        when(jdbc.queryForList(contains("total_pacientes"), any(Object[].class))).thenReturn(List.of());
        assertThat(kpiService.resumoDoUsuario()).isEmpty();
    }
}

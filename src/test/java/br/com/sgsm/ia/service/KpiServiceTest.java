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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KpiServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private KpiService kpiService;

    @BeforeEach
    void setUp() {
        kpiService = new KpiService(jdbc);
    }

    @Test
    void deveConsolidarTodosOsKpis() {
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
}

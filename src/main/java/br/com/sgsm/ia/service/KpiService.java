package br.com.sgsm.ia.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class KpiService {

    private final JdbcTemplate jdbc;

    public KpiService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> resumoExecutivo() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM crm.mv_resumo_executivo LIMIT 1");
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> faturamentoMensal() {
        return jdbc.queryForList("SELECT * FROM crm.v_faturamento_mensal ORDER BY mes DESC LIMIT 12");
    }

    public List<Map<String, Object>> ocupacaoAgenda() {
        return jdbc.queryForList("SELECT * FROM crm.v_ocupacao_agenda ORDER BY medico_nome ASC");
    }

    public List<Map<String, Object>> pacientesAltoValor() {
        return jdbc.queryForList("SELECT * FROM crm.v_alto_valor LIMIT 50");
    }

    public List<Map<String, Object>> churnRisco() {
        return jdbc.queryForList("SELECT * FROM crm.v_churn_risco LIMIT 50");
    }

    public List<Map<String, Object>> funilMedico() {
        return jdbc.queryForList("SELECT * FROM crm.v_funil_medico");
    }

    public List<Map<String, Object>> cancelamentos() {
        return jdbc.queryForList("SELECT * FROM crm.v_cancelamentos ORDER BY mes DESC LIMIT 30");
    }

    public Map<String, Object> consolidado() {
        return Map.of(
                "resumo", resumoExecutivo(),
                "faturamentoMensal", faturamentoMensal(),
                "ocupacaoAgenda", ocupacaoAgenda(),
                "pacientesAltoValor", pacientesAltoValor(),
                "churnRisco", churnRisco(),
                "funilMedico", funilMedico(),
                "cancelamentos", cancelamentos()
        );
    }
}

package br.com.sgsm.ia.service;

import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.security.EscopoAcessoService.Subconsulta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Os métodos sem sufixo leem as views globais do schema crm (sistema inteiro) — usados por
// DESENVOLVEDOR e pela indexação analítica do RAG (DocumentoBuilder/scheduler, sem usuário
// autenticado). Os métodos "*Escopo" recalculam os mesmos indicadores, com as mesmas colunas
// das views, só sobre os agendamentos dos estabelecimentos permitidos ao usuário.
@Service
public class KpiService {

    private final JdbcTemplate jdbc;
    private final EscopoAcessoService escopo;

    public KpiService(JdbcTemplate jdbc, EscopoAcessoService escopo) {
        this.jdbc = jdbc;
        this.escopo = escopo;
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

    // /ia/kpis — global para DESENVOLVEDOR, escopado para os demais papéis
    public Map<String, Object> consolidado() {
        Subconsulta agendamentos = escopo.predicadoAgendamento("a");
        if (agendamentos == null) {
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
        Subconsulta medicos = escopo.medicos();
        return Map.of(
                "resumo", resumoEscopo(agendamentos, medicos),
                "faturamentoMensal", faturamentoMensalEscopo(agendamentos),
                "ocupacaoAgenda", ocupacaoAgendaEscopo(agendamentos, medicos),
                "pacientesAltoValor", pacientesAltoValorEscopo(agendamentos),
                "churnRisco", churnRiscoEscopo(agendamentos, 50),
                "funilMedico", funilMedicoEscopo(agendamentos, medicos),
                "cancelamentos", cancelamentosEscopo(agendamentos)
        );
    }

    // /crm/churn — global para DESENVOLVEDOR, escopado para os demais papéis
    public List<Map<String, Object>> churnRiscoDoUsuario(int limite) {
        Subconsulta agendamentos = escopo.predicadoAgendamento("a");
        if (agendamentos == null) {
            return jdbc.queryForList("SELECT * FROM crm.v_churn_risco LIMIT " + limite);
        }
        return churnRiscoEscopo(agendamentos, limite);
    }

    // Resumo escopado em texto, para contexto do assistente (o resumo ANALITICO do Milvus é
    // global e só vai para DESENVOLVEDOR)
    public Map<String, Object> resumoDoUsuario() {
        Subconsulta agendamentos = escopo.predicadoAgendamento("a");
        if (agendamentos == null) {
            return resumoExecutivo();
        }
        return resumoEscopo(agendamentos, escopo.medicos());
    }

    private Map<String, Object> resumoEscopo(Subconsulta agendamentos, Subconsulta medicos) {
        String sql = """
                SELECT count(a.id) AS total_agendamentos,
                       count(a.id) FILTER (WHERE a.status = 'CONCLUIDO') AS concluidos,
                       count(a.id) FILTER (WHERE a.status = 'CANCELADO') AS cancelados,
                       count(a.id) FILTER (WHERE a.status = 'NO_SHOW') AS no_shows,
                       round(count(a.id) FILTER (WHERE a.status = 'CONCLUIDO')::numeric
                             / NULLIF(count(a.id), 0)::numeric * 100, 2) AS taxa_conversao_pct,
                       COALESCE(sum(pg.valor) FILTER (WHERE pg.status = 'APROVADO'), 0) AS receita_total,
                       COALESCE(avg(pg.valor) FILTER (WHERE pg.status = 'APROVADO'), 0) AS ticket_medio_geral,
                       count(DISTINCT a.paciente_id) AS total_pacientes,
                       count(DISTINCT a.paciente_id) FILTER (WHERE p.criado_em >= now() - interval '30 days') AS novos_pacientes_30d,
                       (SELECT count(*) FROM sgsm.medico m WHERE m.ativo = true AND m.id IN (%s)) AS medicos_ativos,
                       now() AS atualizado_em
                FROM sgsm.agendamento a
                JOIN sgsm.paciente p ON p.id = a.paciente_id
                LEFT JOIN sgsm.pagamento pg ON pg.id = a.pagamento_id
                WHERE %s
                """.formatted(medicos.sql(), agendamentos.sql());
        var rows = jdbc.queryForList(sql, concat(medicos, agendamentos));
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private List<Map<String, Object>> faturamentoMensalEscopo(Subconsulta agendamentos) {
        String sql = """
                SELECT date_trunc('month', pg.criado_em)::date AS mes,
                       count(pg.id) AS total_transacoes,
                       count(pg.id) FILTER (WHERE pg.status = 'APROVADO') AS aprovados,
                       count(pg.id) FILTER (WHERE pg.status IN ('RECUSADO', 'CANCELADO')) AS recusados,
                       COALESCE(sum(pg.valor) FILTER (WHERE pg.status = 'APROVADO'), 0) AS receita_bruta,
                       COALESCE(sum(pg.valor) FILTER (WHERE pg.metodo = 'PIX' AND pg.status = 'APROVADO'), 0) AS receita_pix,
                       COALESCE(sum(pg.valor) FILTER (WHERE pg.metodo = 'CARTAO_CREDITO' AND pg.status = 'APROVADO'), 0) AS receita_cartao,
                       COALESCE(sum(r.valor) FILTER (WHERE r.status = 'CONCLUIDO'), 0) AS total_reembolsos,
                       round(avg(pg.valor) FILTER (WHERE pg.status = 'APROVADO'), 2) AS ticket_medio
                FROM sgsm.pagamento pg
                JOIN sgsm.agendamento a ON a.pagamento_id = pg.id
                LEFT JOIN sgsm.reembolso r ON r.pagamento_id = pg.id
                WHERE %s
                GROUP BY date_trunc('month', pg.criado_em)
                ORDER BY mes DESC
                LIMIT 12
                """.formatted(agendamentos.sql());
        return jdbc.queryForList(sql, agendamentos.params().toArray());
    }

    private List<Map<String, Object>> ocupacaoAgendaEscopo(Subconsulta agendamentos, Subconsulta medicos) {
        String sql = """
                SELECT m.id AS medico_id, m.nome AS medico_nome, m.especialidade,
                       count(a.id) FILTER (WHERE a.status NOT IN ('CANCELADO', 'NO_SHOW')) AS slots_usados,
                       count(a.id) AS slots_totais,
                       round(count(a.id) FILTER (WHERE a.status NOT IN ('CANCELADO', 'NO_SHOW'))::numeric
                             / NULLIF(count(a.id), 0)::numeric * 100, 2) AS taxa_ocupacao_pct
                FROM sgsm.medico m
                LEFT JOIN sgsm.agendamento a ON a.medico_id = m.id AND %s
                WHERE m.ativo = true AND m.id IN (%s)
                GROUP BY m.id, m.nome, m.especialidade
                ORDER BY m.nome ASC
                """.formatted(agendamentos.sql(), medicos.sql());
        return jdbc.queryForList(sql, concat(agendamentos, medicos));
    }

    private List<Map<String, Object>> pacientesAltoValorEscopo(Subconsulta agendamentos) {
        String sql = """
                SELECT p.id AS paciente_id, p.nome, p.email, p.telefone,
                       sum(pg.valor) AS ltv_total,
                       count(pg.id) AS total_pagamentos,
                       ntile(5) OVER (ORDER BY sum(pg.valor)) AS quintil
                FROM sgsm.paciente p
                JOIN sgsm.agendamento a ON a.paciente_id = p.id
                JOIN sgsm.pagamento pg ON pg.id = a.pagamento_id
                WHERE pg.status = 'APROVADO' AND p.ativo = true AND %s
                GROUP BY p.id, p.nome, p.email, p.telefone
                HAVING sum(pg.valor) > 0
                ORDER BY ltv_total DESC
                LIMIT 50
                """.formatted(agendamentos.sql());
        return jdbc.queryForList(sql, agendamentos.params().toArray());
    }

    private List<Map<String, Object>> churnRiscoEscopo(Subconsulta agendamentos, int limite) {
        String sql = """
                SELECT p.id AS paciente_id, p.nome, p.email, p.telefone,
                       max(a.data_hora_inicio) FILTER (WHERE a.status = 'CONCLUIDO') AS ultima_consulta,
                       now() - max(a.data_hora_inicio) FILTER (WHERE a.status = 'CONCLUIDO') AS tempo_inativo,
                       count(a.id) FILTER (WHERE a.status = 'CONCLUIDO') AS total_consultas
                FROM sgsm.paciente p
                JOIN sgsm.agendamento a ON a.paciente_id = p.id
                WHERE p.ativo = true AND %s
                GROUP BY p.id, p.nome, p.email, p.telefone
                HAVING max(a.data_hora_inicio) FILTER (WHERE a.status = 'CONCLUIDO') < now() - interval '90 days'
                ORDER BY ultima_consulta
                LIMIT %d
                """.formatted(agendamentos.sql(), limite);
        return jdbc.queryForList(sql, agendamentos.params().toArray());
    }

    private List<Map<String, Object>> funilMedicoEscopo(Subconsulta agendamentos, Subconsulta medicos) {
        String sql = """
                SELECT m.id AS medico_id, m.nome AS medico_nome, m.especialidade,
                       count(a.id) AS total,
                       count(a.id) FILTER (WHERE a.status = 'CONCLUIDO') AS concluidos,
                       count(a.id) FILTER (WHERE a.status = 'CANCELADO') AS cancelados,
                       count(a.id) FILTER (WHERE a.status = 'NO_SHOW') AS no_shows,
                       round(count(a.id) FILTER (WHERE a.status = 'CONCLUIDO')::numeric
                             / NULLIF(count(a.id), 0)::numeric * 100, 2) AS taxa_conversao_pct,
                       COALESCE(sum(pg.valor) FILTER (WHERE pg.status = 'APROVADO'), 0) AS receita_total
                FROM sgsm.medico m
                LEFT JOIN sgsm.agendamento a ON a.medico_id = m.id AND %s
                LEFT JOIN sgsm.pagamento pg ON pg.id = a.pagamento_id
                WHERE m.ativo = true AND m.id IN (%s)
                GROUP BY m.id, m.nome, m.especialidade
                ORDER BY receita_total DESC
                """.formatted(agendamentos.sql(), medicos.sql());
        return jdbc.queryForList(sql, concat(agendamentos, medicos));
    }

    private List<Map<String, Object>> cancelamentosEscopo(Subconsulta agendamentos) {
        String sql = """
                SELECT a.origem_cancelamento,
                       date_trunc('month', a.atualizado_em)::date AS mes,
                       count(*) AS total_cancelamentos,
                       count(DISTINCT a.paciente_id) AS pacientes_distintos,
                       count(DISTINCT a.medico_id) AS medicos_distintos
                FROM sgsm.agendamento a
                WHERE a.status = 'CANCELADO' AND a.origem_cancelamento IS NOT NULL AND %s
                GROUP BY a.origem_cancelamento, date_trunc('month', a.atualizado_em)
                ORDER BY mes DESC, total_cancelamentos DESC
                LIMIT 30
                """.formatted(agendamentos.sql());
        return jdbc.queryForList(sql, agendamentos.params().toArray());
    }

    // Parâmetros na ordem em que as subconsultas aparecem no texto da query
    private static Object[] concat(Subconsulta primeira, Subconsulta segunda) {
        var params = new ArrayList<>(primeira.params());
        params.addAll(segunda.params());
        return params.toArray();
    }
}

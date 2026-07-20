package br.com.sgsm.ia.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DocumentoBuilder {

    private final JdbcTemplate jdbc;

    public DocumentoBuilder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String construir(String tipo, String id) {
        return switch (tipo.toUpperCase()) {
            case "PACIENTE"         -> construirPaciente(id);
            case "MEDICO"           -> construirMedico(id);
            case "ESTABELECIMENTO"  -> construirEstabelecimento(id);
            case "SERVICO_MEDICO"   -> construirServico(id);
            case "AGENDAMENTO"      -> construirAgendamento(id);
            case "REEMBOLSO"        -> construirReembolso(id);
            default -> "Entidade: " + tipo + " id=" + id;
        };
    }

    private String construirPaciente(String id) {
        var sql = """
            SELECT p.nome, p.cpf, p.data_nascimento, p.email,
                   COUNT(a.id) FILTER (WHERE a.status='CONCLUIDO') AS consultas,
                   COALESCE(SUM(pg.valor) FILTER (WHERE pg.status='APROVADO'), 0) AS ltv,
                   MAX(a.data_hora_inicio) FILTER (WHERE a.status='CONCLUIDO') AS ultimo_agendamento
            FROM sgsm.paciente p
            LEFT JOIN sgsm.agendamento a  ON a.paciente_id = p.id
            LEFT JOIN sgsm.pagamento pg   ON pg.paciente_id = p.id
            WHERE p.id = ?::uuid
            GROUP BY p.id, p.nome, p.cpf, p.data_nascimento, p.email
            """;
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return "Paciente não encontrado: " + id;
            return "Paciente: %s. Email: %s. CPF: %s. Data nascimento: %s. Consultas concluídas: %d. LTV total: R$ %.2f. Último agendamento: %s."
                    .formatted(
                            rs.getString("nome"),
                            rs.getString("email"),
                            rs.getString("cpf"),
                            rs.getObject("data_nascimento"),
                            rs.getLong("consultas"),
                            rs.getDouble("ltv"),
                            rs.getObject("ultimo_agendamento")
                    );
        }, id);
    }

    private String construirMedico(String id) {
        var sql = """
            SELECT m.nome, m.crm, m.crm_uf, m.especialidade, m.email,
                   STRING_AGG(DISTINCT s.nome, ', ') AS servicos,
                   STRING_AGG(DISTINCT e.nome, ', ') AS estabelecimentos
            FROM sgsm.medico m
            LEFT JOIN sgsm.servico_medico s ON s.medico_id = m.id AND s.ativo=true
            LEFT JOIN sgsm.medico_estabelecimento me ON me.medico_id = m.id AND me.ativo=true
            LEFT JOIN sgsm.estabelecimento e ON e.id = me.estabelecimento_id
            WHERE m.id = ?::uuid
            GROUP BY m.id, m.nome, m.crm, m.crm_uf, m.especialidade, m.email
            """;
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return "Médico não encontrado: " + id;
            return "Médico: %s. CRM: %s/%s. Especialidade: %s. Email: %s. Serviços: %s. Estabelecimentos: %s."
                    .formatted(
                            rs.getString("nome"),
                            rs.getString("crm"),
                            rs.getString("crm_uf"),
                            rs.getString("especialidade"),
                            rs.getString("email"),
                            rs.getString("servicos"),
                            rs.getString("estabelecimentos")
                    );
        }, id);
    }

    private String construirEstabelecimento(String id) {
        var sql = """
            SELECT e.nome, e.cnpj, e.cidade, e.uf, e.telefone,
                   STRING_AGG(DISTINCT m.nome, ', ') AS medicos
            FROM sgsm.estabelecimento e
            LEFT JOIN sgsm.medico_estabelecimento me ON me.estabelecimento_id = e.id AND me.ativo=true
            LEFT JOIN sgsm.medico m ON m.id = me.medico_id
            WHERE e.id = ?::uuid
            GROUP BY e.id, e.nome, e.cnpj, e.cidade, e.uf, e.telefone
            """;
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return "Estabelecimento não encontrado: " + id;
            return "Estabelecimento: %s. CNPJ: %s. Cidade: %s/%s. Telefone: %s. Médicos: %s."
                    .formatted(
                            rs.getString("nome"),
                            rs.getString("cnpj"),
                            rs.getString("cidade"),
                            rs.getString("uf"),
                            rs.getString("telefone"),
                            rs.getString("medicos")
                    );
        }, id);
    }

    private String construirServico(String id) {
        var sql = """
            SELECT s.nome, s.descricao, s.valor, s.duracao_minutos, m.nome AS medico, m.especialidade
            FROM sgsm.servico_medico s
            JOIN sgsm.medico m ON m.id = s.medico_id
            WHERE s.id = ?::uuid
            """;
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return "Serviço não encontrado: " + id;
            return "Serviço médico: %s. Médico: %s (%s). Descrição: %s. Valor: R$ %.2f. Duração: %d min."
                    .formatted(
                            rs.getString("nome"),
                            rs.getString("medico"),
                            rs.getString("especialidade"),
                            rs.getString("descricao"),
                            rs.getDouble("valor"),
                            rs.getInt("duracao_minutos")
                    );
        }, id);
    }

    private String construirAgendamento(String id) {
        var sql = """
            SELECT a.observacoes, a.motivo_cancelamento, a.tipo, a.status,
                   a.data_hora_inicio,
                   p.nome AS paciente, m.nome AS medico, m.especialidade,
                   s.nome AS servico, s.descricao
            FROM sgsm.agendamento a
            JOIN sgsm.paciente p       ON p.id = a.paciente_id
            JOIN sgsm.medico m         ON m.id = a.medico_id
            JOIN sgsm.servico_medico s ON s.id = a.servico_medico_id
            WHERE a.id = ?::uuid
            """;
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return "Agendamento não encontrado: " + id;
            return "Agendamento em %s. Paciente: %s. Médico: %s (%s). Serviço: %s. Status: %s. Tipo: %s. Observações: %s. Motivo cancelamento: %s."
                    .formatted(
                            rs.getObject("data_hora_inicio"),
                            rs.getString("paciente"),
                            rs.getString("medico"),
                            rs.getString("especialidade"),
                            rs.getString("servico"),
                            rs.getString("status"),
                            rs.getString("tipo"),
                            rs.getString("observacoes"),
                            rs.getString("motivo_cancelamento")
                    );
        }, id);
    }

    private String construirReembolso(String id) {
        var sql = """
            SELECT r.motivo, r.valor, r.status,
                   p.nome AS paciente, s.nome AS servico
            FROM sgsm.reembolso r
            JOIN sgsm.pagamento pg     ON pg.id = r.pagamento_id
            JOIN sgsm.paciente p       ON p.id = pg.paciente_id
            JOIN sgsm.servico_medico s ON s.id = pg.servico_medico_id
            WHERE r.id = ?::uuid
            """;
        return jdbc.query(sql, rs -> {
            if (!rs.next()) return "Reembolso não encontrado: " + id;
            return "Reembolso. Paciente: %s. Serviço: %s. Valor: R$ %.2f. Status: %s. Motivo: %s."
                    .formatted(
                            rs.getString("paciente"),
                            rs.getString("servico"),
                            rs.getDouble("valor"),
                            rs.getString("status"),
                            rs.getString("motivo")
                    );
        }, id);
    }
}

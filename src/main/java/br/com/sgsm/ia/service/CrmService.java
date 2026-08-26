package br.com.sgsm.ia.service;

import br.com.sgsm.ia.dto.AtualizarStatusLeadRequest;
import br.com.sgsm.ia.dto.ContatoRequest;
import br.com.sgsm.ia.dto.LeadRequest;
import br.com.sgsm.ia.dto.NotaClinicaRequest;
import br.com.sgsm.ia.dto.TagRequest;
import br.com.sgsm.ia.security.ContextoSeguranca;
import br.com.sgsm.ia.security.NotaClinicaCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CrmService {

    private static final Logger log = LoggerFactory.getLogger(CrmService.class);

    private final JdbcTemplate jdbc;
    private final ContextoSeguranca contexto;
    private final DocumentoBuilder documentoBuilder;
    private final MilvusIndexService milvusIndexService;
    private final NotaClinicaCryptoService notaClinicaCryptoService;

    public CrmService(JdbcTemplate jdbc,
                      ContextoSeguranca contexto,
                      DocumentoBuilder documentoBuilder,
                      MilvusIndexService milvusIndexService,
                      NotaClinicaCryptoService notaClinicaCryptoService) {
        this.jdbc = jdbc;
        this.contexto = contexto;
        this.documentoBuilder = documentoBuilder;
        this.milvusIndexService = milvusIndexService;
        this.notaClinicaCryptoService = notaClinicaCryptoService;
    }

    private void reindexarPaciente(String pacienteId) {
        try {
            milvusIndexService.upsert("PACIENTE", pacienteId, documentoBuilder.construir("PACIENTE", pacienteId));
        } catch (Exception e) {
            log.warn("Falha ao reindexar paciente id={}: {}", pacienteId, e.getMessage());
        }
    }

    private void indexarLead(String leadId) {
        try {
            milvusIndexService.upsert("LEAD", leadId, documentoBuilder.construir("LEAD", leadId));
        } catch (Exception e) {
            log.warn("Falha ao indexar lead id={}: {}", leadId, e.getMessage());
        }
    }

    // ── LEADS ─────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listarLeads(String status, String origem) {
        var params = new ArrayList<>();
        var sql = new StringBuilder("""
                SELECT id::text, nome, email, telefone, interesse,
                       origem::text, status::text, observacoes, criado_em, atualizado_em
                FROM crm.lead WHERE 1=1
                """);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?::crm.status_lead");
            params.add(status.toUpperCase());
        }
        if (origem != null && !origem.isBlank()) {
            sql.append(" AND origem = ?::crm.origem_lead");
            params.add(origem.toUpperCase());
        }
        sql.append(" ORDER BY criado_em DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public Map<String, Object> criarLead(LeadRequest req) {
        String usuarioId = contexto.getUsuarioId();
        String id = UUID.randomUUID().toString();
        String origemVal = (req.origem() != null && !req.origem().isBlank())
                ? req.origem().toUpperCase() : "OUTRO";
        jdbc.update("""
                INSERT INTO crm.lead
                       (id, nome, email, telefone, interesse, origem, status, responsavel_id, observacoes)
                VALUES (?::uuid, ?, ?, ?, ?, ?::crm.origem_lead, 'NOVO'::crm.status_lead, ?::uuid, ?)
                """,
                id, req.nome(), req.email(), req.telefone(), req.interesse(),
                origemVal, usuarioId, req.observacoes());
        indexarLead(id);
        return Map.of("id", id, "status", "NOVO");
    }

    public void atualizarStatusLead(String id, AtualizarStatusLeadRequest req) {
        jdbc.update("""
                UPDATE crm.lead
                SET status = ?::crm.status_lead,
                    observacoes = COALESCE(?, observacoes)
                WHERE id = ?::uuid
                """,
                req.status().toUpperCase(), req.observacoes(), id);
        indexarLead(id);
    }

    // ── TAGS ──────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listarTags(String pacienteId) {
        return jdbc.queryForList("""
                SELECT id::text, paciente_id::text, tag, tipo::text, criado_em
                FROM crm.tag_paciente
                WHERE paciente_id = ?::uuid
                ORDER BY criado_em DESC
                """, pacienteId);
    }

    public void adicionarTag(String pacienteId, TagRequest req) {
        String usuarioId = contexto.getUsuarioId();
        jdbc.update("""
                INSERT INTO crm.tag_paciente (id, paciente_id, tag, tipo, criado_por)
                VALUES (gen_random_uuid(), ?::uuid, ?, 'MANUAL'::crm.tipo_tag, ?::uuid)
                ON CONFLICT (paciente_id, tag) DO NOTHING
                """,
                pacienteId, req.tag().toLowerCase().trim(), usuarioId);
        reindexarPaciente(pacienteId);
    }

    public void removerTag(String tagId) {
        var rows = jdbc.queryForList(
                "SELECT paciente_id::text FROM crm.tag_paciente WHERE id = ?::uuid", tagId);
        jdbc.update("DELETE FROM crm.tag_paciente WHERE id = ?::uuid", tagId);
        if (!rows.isEmpty()) {
            reindexarPaciente((String) rows.get(0).get("paciente_id"));
        }
    }

    // ── CONTATOS ──────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listarContatos(String pacienteId) {
        return jdbc.queryForList("""
                SELECT id::text, paciente_id::text, tipo::text, direcao::text,
                       descricao, duracao_segundos, criado_em
                FROM crm.contato_paciente
                WHERE paciente_id = ?::uuid
                ORDER BY criado_em DESC
                """, pacienteId);
    }

    public void registrarContato(String pacienteId, ContatoRequest req) {
        String usuarioId = contexto.getUsuarioId();
        jdbc.update("""
                INSERT INTO crm.contato_paciente
                       (id, paciente_id, tipo, direcao, descricao, duracao_segundos, usuario_id)
                VALUES (gen_random_uuid(), ?::uuid, ?::crm.tipo_contato, ?::crm.direcao_contato, ?, ?, ?::uuid)
                """,
                pacienteId, req.tipo().toUpperCase(), req.direcao().toUpperCase(),
                req.descricao(), req.duracaoSegundos(), usuarioId);
        reindexarPaciente(pacienteId);
    }

    // ── NOTAS CLÍNICAS ────────────────────────────────────────────────────────

    public List<Map<String, Object>> listarNotas(String pacienteId) {
        var notas = jdbc.queryForList("""
                SELECT nc.id::text, nc.paciente_id::text, nc.medico_id::text,
                       nc.tipo::text, nc.conteudo, nc.criado_em,
                       m.nome AS medico_nome
                FROM crm.nota_clinica nc
                LEFT JOIN sgsm.medico m ON m.id = nc.medico_id
                WHERE nc.paciente_id = ?::uuid
                ORDER BY nc.criado_em DESC
                """, pacienteId);
        return notas.stream()
                .map(nota -> {
                    var copia = new LinkedHashMap<>(nota);
                    copia.put("conteudo", notaClinicaCryptoService.decrypt((String) copia.get("conteudo")));
                    return (Map<String, Object>) copia;
                })
                .toList();
    }

    public void adicionarNota(String pacienteId, NotaClinicaRequest req) {
        String medicoId = contexto.isMedico() ? contexto.getReferenciaIdStr() : null;
        String agendId = (req.agendamentoId() != null && !req.agendamentoId().isBlank())
                ? req.agendamentoId() : null;
        jdbc.update("""
                INSERT INTO crm.nota_clinica (id, agendamento_id, paciente_id, medico_id, tipo, conteudo)
                VALUES (gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, ?::crm.tipo_nota, ?)
                """,
                agendId, pacienteId, medicoId, req.tipo().toUpperCase(),
                notaClinicaCryptoService.encrypt(req.conteudo()));
        reindexarPaciente(pacienteId);
    }

    // ── PACIENTE 360 ──────────────────────────────────────────────────────────

    public Map<String, Object> paciente360(String pacienteId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM crm.v_paciente_360 WHERE paciente_id = ?::uuid", pacienteId);
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception e) {
            log.warn("Falha ao consultar v_paciente_360 para pacienteId={}: {}", pacienteId, e.getMessage());
            return Map.of();
        }
    }

    // ── CHURN ─────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> churnRisco() {
        return jdbc.queryForList("SELECT * FROM crm.v_churn_risco LIMIT 100");
    }
}

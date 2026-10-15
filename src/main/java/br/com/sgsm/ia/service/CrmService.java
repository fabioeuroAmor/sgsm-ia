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

    private static final int LIMITE_CHURN = 100;

    private final JdbcTemplate jdbc;
    private final ContextoSeguranca contexto;
    private final DocumentoBuilder documentoBuilder;
    private final MilvusIndexService milvusIndexService;
    private final NotaClinicaCryptoService notaClinicaCryptoService;
    private final EscopoAcessoService escopo;
    private final KpiService kpiService;
    private final AuditoriaService auditoriaService;

    public CrmService(JdbcTemplate jdbc,
                      ContextoSeguranca contexto,
                      DocumentoBuilder documentoBuilder,
                      MilvusIndexService milvusIndexService,
                      NotaClinicaCryptoService notaClinicaCryptoService,
                      EscopoAcessoService escopo,
                      KpiService kpiService,
                      AuditoriaService auditoriaService) {
        this.jdbc = jdbc;
        this.contexto = contexto;
        this.documentoBuilder = documentoBuilder;
        this.milvusIndexService = milvusIndexService;
        this.notaClinicaCryptoService = notaClinicaCryptoService;
        this.escopo = escopo;
        this.kpiService = kpiService;
        this.auditoriaService = auditoriaService;
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
                       origem::text, status::text, observacoes, estabelecimento_id::text,
                       criado_em, atualizado_em
                FROM crm.lead WHERE 1=1
                """);
        Subconsulta estabelecimentos = escopo.estabelecimentos();
        if (estabelecimentos != null) {
            sql.append(" AND estabelecimento_id IN (").append(estabelecimentos.sql()).append(")");
            params.addAll(estabelecimentos.params());
        }
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
        // DESENVOLVEDOR pode criar lead sem estabelecimento; os demais papéis precisam
        // informar um estabelecimento que administram
        if (req.estabelecimentoId() != null || escopo.estabelecimentos() != null) {
            escopo.validarEstabelecimentoPermitido(req.estabelecimentoId());
        }
        String usuarioId = contexto.getUsuarioId();
        String id = UUID.randomUUID().toString();
        String origemVal = (req.origem() != null && !req.origem().isBlank())
                ? req.origem().toUpperCase() : "OUTRO";
        jdbc.update("""
                INSERT INTO crm.lead
                       (id, nome, email, telefone, interesse, origem, status, responsavel_id, observacoes, estabelecimento_id)
                VALUES (?::uuid, ?, ?, ?, ?, ?::crm.origem_lead, 'NOVO'::crm.status_lead, ?::uuid, ?, ?::uuid)
                """,
                id, req.nome(), req.email(), req.telefone(), req.interesse(),
                origemVal, usuarioId, req.observacoes(), req.estabelecimentoId());
        indexarLead(id);
        return Map.of("id", id, "status", "NOVO");
    }

    public void atualizarStatusLead(String id, AtualizarStatusLeadRequest req) {
        validarLeadNoEscopo(id);
        jdbc.update("""
                UPDATE crm.lead
                SET status = ?::crm.status_lead,
                    observacoes = COALESCE(?, observacoes)
                WHERE id = ?::uuid
                """,
                req.status().toUpperCase(), req.observacoes(), id);
        indexarLead(id);
    }

    private void validarLeadNoEscopo(String leadId) {
        Subconsulta estabelecimentos = escopo.estabelecimentos();
        if (estabelecimentos == null) {
            return;
        }
        boolean permitido = escopo.existe(
                "SELECT EXISTS (SELECT 1 FROM crm.lead WHERE id = ?::uuid AND estabelecimento_id IN ("
                        + estabelecimentos.sql() + "))",
                estabelecimentos.comParametros(leadId));
        if (!permitido) {
            throw new RecursoNaoEncontradoException("Lead não encontrado: " + leadId);
        }
    }

    // ── TAGS ──────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listarTags(String pacienteId) {
        escopo.validarPacienteNoEscopo(pacienteId);
        return jdbc.queryForList("""
                SELECT id::text, paciente_id::text, tag, tipo::text, criado_em
                FROM crm.tag_paciente
                WHERE paciente_id = ?::uuid
                ORDER BY criado_em DESC
                """, pacienteId);
    }

    public void adicionarTag(String pacienteId, TagRequest req) {
        escopo.validarPacienteNoEscopo(pacienteId);
        String usuarioId = contexto.getUsuarioId();
        jdbc.update("""
                INSERT INTO crm.tag_paciente (id, paciente_id, tag, tipo, criado_por)
                VALUES (gen_random_uuid(), ?::uuid, ?, 'MANUAL'::crm.tipo_tag, ?::uuid)
                ON CONFLICT (paciente_id, tag) DO NOTHING
                """,
                pacienteId, req.tag().toLowerCase().trim(), usuarioId);
        reindexarPaciente(pacienteId);
    }

    // A rota só recebe o tagId: resolve o paciente dono da tag e valida o escopo antes de apagar
    public void removerTag(String tagId) {
        var rows = jdbc.queryForList(
                "SELECT paciente_id::text FROM crm.tag_paciente WHERE id = ?::uuid", tagId);
        if (rows.isEmpty()) {
            throw new RecursoNaoEncontradoException("Tag não encontrada: " + tagId);
        }
        String pacienteId = (String) rows.get(0).get("paciente_id");
        if (!escopo.pacienteNoEscopo(pacienteId)) {
            throw new RecursoNaoEncontradoException("Tag não encontrada: " + tagId);
        }
        jdbc.update("DELETE FROM crm.tag_paciente WHERE id = ?::uuid", tagId);
        reindexarPaciente(pacienteId);
    }

    // ── CONTATOS ──────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listarContatos(String pacienteId) {
        escopo.validarPacienteNoEscopo(pacienteId);
        return jdbc.queryForList("""
                SELECT id::text, paciente_id::text, tipo::text, direcao::text,
                       descricao, duracao_segundos, criado_em
                FROM crm.contato_paciente
                WHERE paciente_id = ?::uuid
                ORDER BY criado_em DESC
                """, pacienteId);
    }

    public void registrarContato(String pacienteId, ContatoRequest req) {
        escopo.validarPacienteNoEscopo(pacienteId);
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

    // Sigilo médico: só o médico que atendeu o paciente (ou DESENVOLVEDOR) lê o conteúdo
    // decifrado; toda leitura fica registrada em sgsm.log_acesso
    public List<Map<String, Object>> listarNotas(String pacienteId) {
        escopo.validarPacienteAtendidoPeloMedico(pacienteId);
        var notas = jdbc.queryForList("""
                SELECT nc.id::text, nc.paciente_id::text, nc.medico_id::text,
                       nc.tipo::text, nc.conteudo, nc.criado_em,
                       m.nome AS medico_nome
                FROM crm.nota_clinica nc
                LEFT JOIN sgsm.medico m ON m.id = nc.medico_id
                WHERE nc.paciente_id = ?::uuid
                ORDER BY nc.criado_em DESC
                """, pacienteId);
        auditoriaService.registrarLeitura("NOTA_CLINICA", pacienteId);
        return notas.stream()
                .map(nota -> {
                    var copia = new LinkedHashMap<>(nota);
                    copia.put("conteudo", notaClinicaCryptoService.decrypt((String) copia.get("conteudo")));
                    return (Map<String, Object>) copia;
                })
                .toList();
    }

    public void adicionarNota(String pacienteId, NotaClinicaRequest req) {
        if (!contexto.isMedico()) {
            throw new AcessoNegadoException("Somente médicos registram notas clínicas");
        }
        escopo.validarPacienteAtendidoPeloMedico(pacienteId);
        String medicoId = contexto.getReferenciaIdStr();
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
        escopo.validarPacienteNoEscopo(pacienteId);
        auditoriaService.registrarLeitura("PACIENTE_360", pacienteId);
        try {
            Subconsulta agendamentos = escopo.predicadoAgendamento("a");
            List<Map<String, Object>> rows = agendamentos == null
                    ? jdbc.queryForList("SELECT * FROM crm.v_paciente_360 WHERE paciente_id = ?::uuid", pacienteId)
                    : jdbc.queryForList(sqlPaciente360Escopo(agendamentos), append(agendamentos.params(), pacienteId));
            return rows.isEmpty() ? Map.of() : rows.get(0);
        } catch (Exception e) {
            log.warn("Falha ao consultar visão 360 para pacienteId={}: {}", pacienteId, e.getMessage());
            return Map.of();
        }
    }

    // Mesmas colunas de crm.v_paciente_360, mas agendamentos/pagamentos só do escopo do usuário —
    // senão o staff de uma clínica enxergaria o histórico e o LTV do paciente em outra clínica
    private static String sqlPaciente360Escopo(Subconsulta agendamentos) {
        return """
                SELECT p.id AS paciente_id, p.nome, p.email, p.telefone,
                       date_part('year', age(p.data_nascimento::timestamptz))::integer AS idade,
                       p.ativo, p.criado_em AS cadastrado_em,
                       count(a.id) AS total_agendamentos,
                       count(a.id) FILTER (WHERE a.status = 'CONCLUIDO') AS consultas_concluidas,
                       count(a.id) FILTER (WHERE a.status = 'CANCELADO') AS cancelamentos,
                       count(a.id) FILTER (WHERE a.status = 'NO_SHOW') AS no_shows,
                       max(a.data_hora_inicio) FILTER (WHERE a.status = 'CONCLUIDO') AS ultima_consulta,
                       COALESCE(sum(pg.valor) FILTER (WHERE pg.status = 'APROVADO'), 0) AS ltv_total,
                       COALESCE(avg(pg.valor) FILTER (WHERE pg.status = 'APROVADO'), 0) AS ticket_medio,
                       count(pg.id) FILTER (WHERE pg.metodo = 'PIX') AS pagamentos_pix,
                       count(pg.id) FILTER (WHERE pg.metodo = 'CARTAO_CREDITO') AS pagamentos_cartao
                FROM sgsm.paciente p
                LEFT JOIN sgsm.agendamento a ON a.paciente_id = p.id AND %s
                LEFT JOIN sgsm.pagamento pg ON pg.id = a.pagamento_id
                WHERE p.id = ?::uuid
                GROUP BY p.id, p.nome, p.email, p.telefone, p.data_nascimento, p.ativo, p.criado_em
                """.formatted(agendamentos.sql());
    }

    private static Object[] append(List<Object> params, Object ultimo) {
        var todos = new ArrayList<>(params);
        todos.add(ultimo);
        return todos.toArray();
    }

    // ── CHURN ─────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> churnRisco() {
        return kpiService.churnRiscoDoUsuario(LIMITE_CHURN);
    }
}

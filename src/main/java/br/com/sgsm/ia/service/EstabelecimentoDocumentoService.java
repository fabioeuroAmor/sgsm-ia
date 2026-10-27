package br.com.sgsm.ia.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

// Resolve a(s) tag(s) de estabelecimento gravadas no metadata de cada vetor do Milvus, para que a
// busca semântica já filtre por clínica no próprio Milvus (Fase 8 do plano de correção).
// Mesmas regras de escopo do EscopoAcessoService:
//  - catálogo (médico, estabelecimento, serviço): PUBLICO + as clínicas em que é oferecido (médico e
//    serviço: clínicas do médico; estabelecimento: ele mesmo) — usado pelo WhatsApp de cada clínica
//  - agregados analíticos do sistema inteiro: GLOBAL (só DESENVOLVEDOR)
//  - paciente: clínicas dos médicos que o atenderam (presencial ou domiciliar)
//  - agendamento/reembolso: clínica do atendimento; domiciliar = clínicas do médico (D4)
//  - lead: estabelecimento do lead
//  - sem nenhuma clínica: NENHUM (só DESENVOLVEDOR)
// A tag é só um pré-filtro: a regra que decide continua no RagAutorizacaoService, então uma tag
// desatualizada pode esconder um documento, mas nunca vazá-lo.
@Service
public class EstabelecimentoDocumentoService {

    public static final String CHAVE_METADATA = "estabelecimento_id";
    public static final String TAG_PUBLICO = "PUBLICO";
    public static final String TAG_GLOBAL = "GLOBAL";
    public static final String TAG_NENHUM = "NENHUM";

    private static final String CLINICAS_DO_AGENDAMENTO = """
            SELECT a.estabelecimento_id::text FROM sgsm.agendamento a
            WHERE a.id = ?::uuid AND a.estabelecimento_id IS NOT NULL
            UNION
            SELECT me.estabelecimento_id::text FROM sgsm.agendamento a
            JOIN sgsm.medico_estabelecimento me ON me.medico_id = a.medico_id AND me.ativo = true
            WHERE a.id = ?::uuid AND a.estabelecimento_id IS NULL
            """;

    private static final String CLINICAS_DO_MEDICO =
            "SELECT estabelecimento_id::text FROM sgsm.medico_estabelecimento WHERE medico_id = ?::uuid AND ativo = true";
    private static final String CLINICAS_DO_SERVICO =
            "SELECT me.estabelecimento_id::text FROM sgsm.servico_medico s "
            + "JOIN sgsm.medico_estabelecimento me ON me.medico_id = s.medico_id AND me.ativo = true "
            + "WHERE s.id = ?::uuid";

    private final JdbcTemplate jdbc;

    public EstabelecimentoDocumentoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> tags(String tipo, String referenciaId) {
        List<String> clinicas = switch (tipo.toUpperCase()) {
            case "MEDICO" -> catalogo(jdbc.queryForList(CLINICAS_DO_MEDICO, String.class, referenciaId));
            case "SERVICO_MEDICO" -> catalogo(jdbc.queryForList(CLINICAS_DO_SERVICO, String.class, referenciaId));
            case "ESTABELECIMENTO" -> catalogo(List.of(referenciaId));
            case "ANALITICO" -> List.of(TAG_GLOBAL);
            case "PACIENTE" -> jdbc.queryForList("""
                    SELECT DISTINCT me.estabelecimento_id::text FROM sgsm.agendamento a
                    JOIN sgsm.medico_estabelecimento me ON me.medico_id = a.medico_id AND me.ativo = true
                    WHERE a.paciente_id = ?::uuid
                    """, String.class, referenciaId);
            case "AGENDAMENTO" -> jdbc.queryForList(CLINICAS_DO_AGENDAMENTO, String.class, referenciaId, referenciaId);
            case "REEMBOLSO" -> jdbc.queryForList("""
                    SELECT DISTINCT x.id FROM (
                        SELECT a.estabelecimento_id::text AS id FROM sgsm.reembolso r
                        JOIN sgsm.agendamento a ON a.pagamento_id = r.pagamento_id
                        WHERE r.id = ?::uuid AND a.estabelecimento_id IS NOT NULL
                        UNION
                        SELECT me.estabelecimento_id::text FROM sgsm.reembolso r
                        JOIN sgsm.agendamento a ON a.pagamento_id = r.pagamento_id
                        JOIN sgsm.medico_estabelecimento me ON me.medico_id = a.medico_id AND me.ativo = true
                        WHERE r.id = ?::uuid AND a.estabelecimento_id IS NULL
                    ) x
                    """, String.class, referenciaId, referenciaId);
            case "LEAD" -> jdbc.queryForList(
                    "SELECT estabelecimento_id::text FROM crm.lead WHERE id = ?::uuid AND estabelecimento_id IS NOT NULL",
                    String.class, referenciaId);
            default -> List.of();
        };
        return clinicas.isEmpty() ? List.of(TAG_NENHUM) : clinicas;
    }

    private static List<String> catalogo(List<String> clinicas) {
        var tags = new java.util.ArrayList<String>();
        tags.add(TAG_PUBLICO);
        tags.addAll(clinicas);
        return tags;
    }

    // Serviços ativos de um médico: reindexados junto com ele quando as clínicas dele mudam
    public List<String> servicosDoMedico(String medicoId) {
        return jdbc.queryForList("SELECT id::text FROM sgsm.servico_medico WHERE medico_id = ?::uuid AND ativo = true",
                String.class, medicoId);
    }
}

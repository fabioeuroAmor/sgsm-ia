package br.com.sgsm.ia.service;

import br.com.sgsm.ia.config.IaProperties;
import br.com.sgsm.ia.security.ContextoSeguranca;
import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.security.EscopoAcessoService.Subconsulta;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

// Camada de autorização do RAG: o índice do Milvus é único para todas as clínicas, então toda
// busca semântica feita em nome de um usuário passa por aqui. Busca mais que o top-K
// (overfetch), descarta os documentos que o usuário não pode ver e só então corta no top-K —
// para que usuários de clínicas pequenas não fiquem com contexto vazio.
@Service
public class RagAutorizacaoService {

    static final int FATOR_OVERFETCH = 4;

    // Catálogo público: médicos, estabelecimentos e serviços são ofertados a qualquer usuário
    static final Set<String> TIPOS_CATALOGO = Set.of("MEDICO", "ESTABELECIMENTO", "SERVICO_MEDICO");

    private final MilvusIndexService milvusIndexService;
    private final EscopoAcessoService escopo;
    private final ContextoSeguranca contexto;
    private final IaProperties iaProps;

    public RagAutorizacaoService(MilvusIndexService milvusIndexService,
                                 EscopoAcessoService escopo,
                                 ContextoSeguranca contexto,
                                 IaProperties iaProps) {
        this.milvusIndexService = milvusIndexService;
        this.escopo = escopo;
        this.contexto = contexto;
        this.iaProps = iaProps;
    }

    // /ia/chat e /ia/busca. Documentos ANALITICO (agregados do sistema inteiro) só para
    // DESENVOLVEDOR — nem chegam a ser buscados para os demais papéis.
    public List<EmbeddingMatch<TextSegment>> buscar(String pergunta, String tipo) {
        boolean desenvolvedor = contexto.isDesenvolvedor();
        String tipoNormalizado = (tipo != null && !tipo.isBlank()) ? tipo.toUpperCase() : null;
        if (!desenvolvedor && "ANALITICO".equals(tipoNormalizado)) {
            return List.of();
        }
        Filter filtro;
        if (tipoNormalizado != null) {
            filtro = MetadataFilterBuilder.metadataKey("tipo").isEqualTo(tipoNormalizado);
        } else {
            filtro = desenvolvedor ? null : MetadataFilterBuilder.metadataKey("tipo").isNotEqualTo("ANALITICO");
        }
        return filtrar(milvusIndexService.buscar(pergunta, filtro, iaProps.topK() * FATOR_OVERFETCH),
                this::podeVer);
    }

    // Canal WhatsApp: só catálogo público, mais os agendamentos do próprio paciente quando o
    // token for de PACIENTE (necessário para a intenção CANCELAR). Nunca dados de terceiros,
    // leads ou agregados — nem mesmo com o token de sistema do bot (DESENVOLVEDOR).
    public List<EmbeddingMatch<TextSegment>> buscarParaWhatsApp(String pergunta) {
        Filter filtro = MetadataFilterBuilder.metadataKey("tipo")
                .isIn("MEDICO", "ESTABELECIMENTO", "SERVICO_MEDICO", "AGENDAMENTO");
        return filtrar(milvusIndexService.buscar(pergunta, filtro, iaProps.topK() * FATOR_OVERFETCH),
                this::podeVerNoWhatsApp);
    }

    boolean podeVer(String tipo, String referenciaId) {
        if (contexto.isDesenvolvedor()) {
            return true;
        }
        if (tipo == null || referenciaId == null) {
            return false;
        }
        if (TIPOS_CATALOGO.contains(tipo)) {
            return true;
        }
        return switch (tipo) {
            // O documento de paciente inclui notas clínicas decifradas: mesma regra do
            // GET /crm/paciente/{id}/notas (só o médico que atendeu)
            case "PACIENTE" -> escopo.pacienteAtendidoPeloMedico(referenciaId);
            case "AGENDAMENTO" -> agendamentoNoEscopo(referenciaId);
            case "REEMBOLSO" -> reembolsoNoEscopo(referenciaId);
            case "LEAD" -> leadNoEscopo(referenciaId);
            default -> false;
        };
    }

    boolean podeVerNoWhatsApp(String tipo, String referenciaId) {
        if (tipo == null || referenciaId == null) {
            return false;
        }
        if (TIPOS_CATALOGO.contains(tipo)) {
            return true;
        }
        String pacienteId = contexto.getReferenciaIdStr();
        if ("AGENDAMENTO".equals(tipo) && contexto.isPaciente() && pacienteId != null) {
            return escopo.existe(
                    "SELECT EXISTS (SELECT 1 FROM sgsm.agendamento WHERE id = ?::uuid AND paciente_id = ?::uuid)",
                    referenciaId, pacienteId);
        }
        return false;
    }

    private boolean agendamentoNoEscopo(String agendamentoId) {
        Subconsulta agendamentos = escopo.predicadoAgendamento("a");
        return escopo.existe("SELECT EXISTS (SELECT 1 FROM sgsm.agendamento a WHERE a.id = ?::uuid AND "
                + agendamentos.sql() + ")", agendamentos.comParametros(agendamentoId));
    }

    private boolean reembolsoNoEscopo(String reembolsoId) {
        Subconsulta agendamentos = escopo.predicadoAgendamento("a");
        return escopo.existe("""
                SELECT EXISTS (SELECT 1 FROM sgsm.reembolso r
                               JOIN sgsm.agendamento a ON a.pagamento_id = r.pagamento_id
                               WHERE r.id = ?::uuid AND %s)
                """.formatted(agendamentos.sql()), agendamentos.comParametros(reembolsoId));
    }

    private boolean leadNoEscopo(String leadId) {
        Subconsulta estabelecimentos = escopo.estabelecimentos();
        return escopo.existe("SELECT EXISTS (SELECT 1 FROM crm.lead WHERE id = ?::uuid AND estabelecimento_id IN ("
                + estabelecimentos.sql() + "))", estabelecimentos.comParametros(leadId));
    }

    private interface Regra {
        boolean permite(String tipo, String referenciaId);
    }

    private List<EmbeddingMatch<TextSegment>> filtrar(List<EmbeddingMatch<TextSegment>> matches, Regra regra) {
        return matches.stream()
                .filter(m -> m.embedded() != null && regra.permite(
                        m.embedded().metadata().getString("tipo"),
                        m.embedded().metadata().getString("referencia_id")))
                .limit(iaProps.topK())
                .toList();
    }
}

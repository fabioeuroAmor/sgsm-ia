package br.com.sgsm.ia.service;

import br.com.sgsm.ia.config.IaProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MilvusIndexService {

    private static final Logger log = LoggerFactory.getLogger(MilvusIndexService.class);

    private final MilvusEmbeddingStore store;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbc;
    private final IaProperties iaProps;
    private final EstabelecimentoDocumentoService estabelecimentoDocumentoService;

    public MilvusIndexService(MilvusEmbeddingStore store,
                              EmbeddingModel embeddingModel,
                              JdbcTemplate jdbc,
                              IaProperties iaProps,
                              EstabelecimentoDocumentoService estabelecimentoDocumentoService) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.iaProps = iaProps;
        this.estabelecimentoDocumentoService = estabelecimentoDocumentoService;
    }

    // Upsert: remove entrada(s) anterior(es) com o mesmo (tipo, referencia_id) e insere o novo vetor.
    // Um vetor por estabelecimento do documento (o metadata do langchain4j não aceita lista) — o
    // embedding é calculado uma vez só; a busca deduplica pelo texto.
    public void upsert(String tipo, String referenciaId, String conteudo) {
        try {
            removerVetorAnterior(tipo, referenciaId);

            Embedding embedding = embeddingModel.embed(TextSegment.from(conteudo)).content();
            String milvusId = null;
            for (String estabelecimento : estabelecimentoDocumentoService.tags(tipo, referenciaId)) {
                String id = store.add(embedding, segmento(conteudo, tipo, referenciaId, estabelecimento));
                if (milvusId == null) {
                    milvusId = id;
                }
            }

            atualizarStatusDocumento(tipo, referenciaId, conteudo, milvusId, "INDEXADO");
            log.info("Indexado no Milvus: tipo={} id={} milvusId={}", tipo, referenciaId, milvusId);
        } catch (Exception e) {
            log.error("Erro ao indexar tipo={} id={}: {}", tipo, referenciaId, e.getMessage());
            incrementarTentativaErro(tipo, referenciaId);
            throw e;
        }
    }

    // Indexa um documento analítico (resumo executivo ou uma das views de KPI) diretamente no
    // Milvus (sem crm.documento — dado agregado/global, não ligado a uma entidade específica)
    public void indexarAnalitico(String referenciaId, String texto) {
        try {
            removerVetorAnterior("ANALITICO", referenciaId);

            TextSegment segmento = segmento(texto, "ANALITICO", referenciaId, EstabelecimentoDocumentoService.TAG_GLOBAL);
            Embedding embedding = embeddingModel.embed(segmento).content();
            store.add(embedding, segmento);
            log.info("Documento analítico '{}' indexado no Milvus", referenciaId);
        } catch (Exception e) {
            log.warn("Falha ao indexar documento analítico '{}': {}", referenciaId, e.getMessage());
        }
    }

    // Documento agregado de uma clínica (ex.: resumo de tráfego pago), sem crm.documento — mesma
    // ideia do indexarAnalitico, mas com a tag da própria clínica em vez de GLOBAL
    public void indexarDocumentoDeClinica(String tipo, String referenciaId, String texto, String estabelecimentoId) {
        removerVetorAnterior(tipo, referenciaId);
        TextSegment segmento = segmento(texto, tipo, referenciaId, estabelecimentoId);
        store.add(embeddingModel.embed(segmento).content(), segmento);
        log.info("Documento {} da clínica {} indexado no Milvus", tipo, estabelecimentoId);
    }

    // Par do indexarDocumentoDeClinica: só o vetor (não há linha em crm.documento para apagar)
    public void removerDocumentoDeClinica(String tipo, String referenciaId) {
        removerVetorAnterior(tipo, referenciaId);
        log.info("Documento {} '{}' removido do Milvus", tipo, referenciaId);
    }

    private static TextSegment segmento(String texto, String tipo, String referenciaId, String estabelecimento) {
        return TextSegment.from(texto, Metadata.from(Map.of(
                "tipo", tipo,
                "referencia_id", referenciaId,
                EstabelecimentoDocumentoService.CHAVE_METADATA, estabelecimento)));
    }

    // Remove por metadata (tipo + referencia_id) todos os vetores indexados anteriormente para essa entidade,
    // evitando acúmulo de versões obsoletas no Milvus a cada reindexação (ETL, consumer ou scheduler)
    private void removerVetorAnterior(String tipo, String referenciaId) {
        Filter filtro = MetadataFilterBuilder.metadataKey("tipo").isEqualTo(tipo)
                .and(MetadataFilterBuilder.metadataKey("referencia_id").isEqualTo(referenciaId));
        store.removeAll(filtro);
    }

    // LGPD 3.3 — direito ao esquecimento: remove o vetor do Milvus e purga a linha de
    // crm.documento (ao contrário do upsert, aqui não há reindexação — o dado já foi
    // anonimizado no Postgres pelo sgsm, então não sobra nada legítimo para vetorizar).
    public void remover(String tipo, String referenciaId) {
        removerVetorAnterior(tipo, referenciaId);
        jdbc.update("DELETE FROM crm.documento WHERE tipo = ?::crm.tipo_documento AND referencia_id = ?::uuid",
                tipo, referenciaId);
        log.info("Removido do Milvus e purgado de crm.documento: tipo={} id={}", tipo, referenciaId);
    }

    // Busca semântica top-K em todos os tipos de documento
    public List<EmbeddingMatch<TextSegment>> buscar(String pergunta) {
        return buscar(pergunta, null);
    }

    // Busca semântica top-K restrita a um tipo de documento (filtro aplicado no próprio Milvus,
    // para que o tipo pedido não seja excluído por concorrer com outros tipos no top-K geral)
    public List<EmbeddingMatch<TextSegment>> buscar(String pergunta, String tipo) {
        Filter filtro = (tipo != null && !tipo.isBlank())
                ? MetadataFilterBuilder.metadataKey("tipo").isEqualTo(tipo.toUpperCase())
                : null;
        return buscar(pergunta, filtro, iaProps.topK());
    }

    // Busca com filtro de metadata e limite explícitos — usada pela camada de autorização do RAG
    // (RagAutorizacaoService), que busca mais que o top-K e corta depois de remover o que o
    // usuário não pode ver
    public List<EmbeddingMatch<TextSegment>> buscar(String pergunta, Filter filtro, int maxResults) {
        Embedding queryEmbedding = embeddingModel.embed(pergunta).content();
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(0.5);
        if (filtro != null) {
            request.filter(filtro);
        }
        return store.search(request.build()).matches();
    }

    private void atualizarStatusDocumento(String tipo, String referenciaId,
                                          String conteudo, String milvusId, String status) {
        jdbc.update("""
            INSERT INTO crm.documento (tipo, referencia_id, referencia_tabela, conteudo, milvus_id, status_indexacao, tentativas)
            VALUES (?::crm.tipo_documento, ?::uuid, ?, ?, ?, ?::crm.status_indexacao, 0)
            ON CONFLICT (tipo, referencia_id) DO UPDATE
            SET conteudo = EXCLUDED.conteudo,
                milvus_id = EXCLUDED.milvus_id,
                status_indexacao = EXCLUDED.status_indexacao::crm.status_indexacao,
                versao = crm.documento.versao + 1,
                tentativas = 0,
                atualizado_em = NOW()
            """,
                tipo, referenciaId, "sgsm." + tipo.toLowerCase(), conteudo, milvusId, status
        );
    }

    private void incrementarTentativaErro(String tipo, String referenciaId) {
        jdbc.update("""
            INSERT INTO crm.documento (tipo, referencia_id, referencia_tabela, conteudo, status_indexacao, tentativas)
            VALUES (?::crm.tipo_documento, ?::uuid, ?, '', 'ERRO'::crm.status_indexacao, 1)
            ON CONFLICT (tipo, referencia_id) DO UPDATE
            SET status_indexacao = 'ERRO'::crm.status_indexacao,
                tentativas = crm.documento.tentativas + 1,
                atualizado_em = NOW()
            """,
                tipo, referenciaId, "sgsm." + tipo.toLowerCase()
        );
    }
}

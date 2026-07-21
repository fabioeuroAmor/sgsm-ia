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

    public MilvusIndexService(MilvusEmbeddingStore store,
                              EmbeddingModel embeddingModel,
                              JdbcTemplate jdbc,
                              IaProperties iaProps) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.iaProps = iaProps;
    }

    // Upsert: remove entrada(s) anterior(es) com o mesmo (tipo, referencia_id) e insere o novo vetor
    public void upsert(String tipo, String referenciaId, String conteudo) {
        try {
            removerVetorAnterior(tipo, referenciaId);

            TextSegment segmento = TextSegment.from(conteudo,
                    Metadata.from(Map.of("tipo", tipo, "referencia_id", referenciaId)));

            Embedding embedding = embeddingModel.embed(segmento).content();
            String milvusId = store.add(embedding, segmento);

            atualizarStatusDocumento(tipo, referenciaId, conteudo, milvusId, "INDEXADO");
            log.info("Indexado no Milvus: tipo={} id={} milvusId={}", tipo, referenciaId, milvusId);
        } catch (Exception e) {
            log.error("Erro ao indexar tipo={} id={}: {}", tipo, referenciaId, e.getMessage());
            incrementarTentativaErro(tipo, referenciaId);
            throw e;
        }
    }

    // Indexa resumo analítico diretamente no Milvus (sem crm.documento — dado global, não por entidade)
    public void indexarAnalitico(String texto) {
        try {
            removerVetorAnterior("ANALITICO", "resumo-analitico");

            TextSegment segmento = TextSegment.from(texto,
                    Metadata.from(Map.of("tipo", "ANALITICO", "referencia_id", "resumo-analitico")));
            Embedding embedding = embeddingModel.embed(segmento).content();
            store.add(embedding, segmento);
            log.info("Resumo analítico (KPI) indexado no Milvus");
        } catch (Exception e) {
            log.warn("Falha ao indexar resumo analítico: {}", e.getMessage());
        }
    }

    // Remove por metadata (tipo + referencia_id) todos os vetores indexados anteriormente para essa entidade,
    // evitando acúmulo de versões obsoletas no Milvus a cada reindexação (ETL, consumer ou scheduler)
    private void removerVetorAnterior(String tipo, String referenciaId) {
        Filter filtro = MetadataFilterBuilder.metadataKey("tipo").isEqualTo(tipo)
                .and(MetadataFilterBuilder.metadataKey("referencia_id").isEqualTo(referenciaId));
        store.removeAll(filtro);
    }

    // Busca semântica top-K em todos os tipos de documento
    public List<EmbeddingMatch<TextSegment>> buscar(String pergunta) {
        return buscar(pergunta, null);
    }

    // Busca semântica top-K restrita a um tipo de documento (filtro aplicado no próprio Milvus,
    // para que o tipo pedido não seja excluído por concorrer com outros tipos no top-K geral)
    public List<EmbeddingMatch<TextSegment>> buscar(String pergunta, String tipo) {
        Embedding queryEmbedding = embeddingModel.embed(pergunta).content();
        var request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(iaProps.topK())
                .minScore(0.5);
        if (tipo != null && !tipo.isBlank()) {
            request.filter(MetadataFilterBuilder.metadataKey("tipo").isEqualTo(tipo.toUpperCase()));
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

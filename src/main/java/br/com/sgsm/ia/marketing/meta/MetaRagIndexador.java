package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.service.MilvusIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

// Fase 7: depois de cada sincronização, indexa no Milvus um resumo normalizado das métricas da
// clínica (tipo MARKETING, tag de estabelecimento = a própria clínica). O RagAutorizacaoService só
// libera esse documento para o ADMIN da clínica e para DESENVOLVEDOR.
@Component
public class MetaRagIndexador {

    private static final Logger log = LoggerFactory.getLogger(MetaRagIndexador.class);

    public static final String TIPO_DOCUMENTO = "MARKETING";

    private final MetaMetricasService metricas;
    private final MilvusIndexService milvus;
    private final Clock clock;

    public MetaRagIndexador(MetaMetricasService metricas, MilvusIndexService milvus, Clock clock) {
        this.metricas = metricas;
        this.milvus = milvus;
        this.clock = clock;
    }

    // Exclusão de dados pedida pela Meta: o resumo usado pelo assistente também é dado da conexão.
    // Aqui a falha propaga — exclusão incompleta não pode ser confirmada à Meta
    public void remover(UUID estabelecimentoId) {
        milvus.removerDocumentoDeClinica(TIPO_DOCUMENTO, estabelecimentoId.toString());
    }

    // Falha de indexação não derruba a sincronização: os dados já estão no banco e o dashboard funciona
    public void indexar(UUID estabelecimentoId) {
        try {
            String texto = metricas.resumoParaAssistente(estabelecimentoId, LocalDate.now(clock));
            milvus.indexarDocumentoDeClinica(TIPO_DOCUMENTO, estabelecimentoId.toString(), texto,
                    estabelecimentoId.toString());
        } catch (RuntimeException e) {
            log.warn("Falha ao indexar resumo de tráfego pago (estabelecimento={}): {}", estabelecimentoId, e.getMessage());
        }
    }
}

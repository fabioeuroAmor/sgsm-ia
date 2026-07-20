package br.com.sgsm.ia.consumer;

import br.com.sgsm.ia.config.IaProperties;
import br.com.sgsm.ia.service.DocumentoBuilder;
import br.com.sgsm.ia.service.MilvusIndexService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class VetorizacaoConsumer {

    private static final Logger log = LoggerFactory.getLogger(VetorizacaoConsumer.class);

    private final StringRedisTemplate redis;
    private final DocumentoBuilder documentoBuilder;
    private final MilvusIndexService milvusIndexService;
    private final IaProperties iaProps;

    public VetorizacaoConsumer(StringRedisTemplate redis,
                               DocumentoBuilder documentoBuilder,
                               MilvusIndexService milvusIndexService,
                               IaProperties iaProps) {
        this.redis = redis;
        this.documentoBuilder = documentoBuilder;
        this.milvusIndexService = milvusIndexService;
        this.iaProps = iaProps;
    }

    @PostConstruct
    public void inicializarConsumerGroup() {
        String streamKey = iaProps.redis().streamKey();
        String group = iaProps.redis().group();
        try {
            redis.opsForStream().createGroup(streamKey, ReadOffset.latest(), group);
            log.info("Consumer group '{}' criado no stream '{}'", group, streamKey);
        } catch (Exception e) {
            // Grupo já existe — normal em restart
            log.debug("Consumer group ja existe: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 500)
    public void processar() {
        String streamKey = iaProps.redis().streamKey();
        String group = iaProps.redis().group();
        String consumer = iaProps.redis().consumer();

        try {
            List<MapRecord<String, Object, Object>> mensagens = redis.opsForStream().read(
                    Consumer.from(group, consumer),
                    StreamReadOptions.empty().count(10).block(Duration.ofMillis(200)),
                    StreamOffset.create(streamKey, ReadOffset.lastConsumed())
            );

            if (mensagens == null || mensagens.isEmpty()) return;

            for (MapRecord<String, Object, Object> msg : mensagens) {
                String tipo = (String) msg.getValue().get("tipo");
                String id = (String) msg.getValue().get("id");
                try {
                    String texto = documentoBuilder.construir(tipo, id);
                    milvusIndexService.upsert(tipo, id, texto);
                    redis.opsForStream().acknowledge(streamKey, group, msg.getId());
                    log.info("Evento processado e ACK: tipo={} id={}", tipo, id);
                } catch (Exception e) {
                    log.warn("Falha ao processar evento tipo={} id={}. Sera reprocessado. Erro: {}", tipo, id, e.getMessage());
                    // SEM ACK → permanece no stream para retry automático
                }
            }
        } catch (Exception e) {
            log.error("Erro no loop do consumer de vetorizacao: {}", e.getMessage());
        }
    }
}

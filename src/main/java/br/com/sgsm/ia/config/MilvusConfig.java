package br.com.sgsm.ia.config;

import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    private final MilvusProperties props;

    public MilvusConfig(MilvusProperties props) {
        this.props = props;
    }

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(props.host())
                        .withPort(props.port())
                        .build()
        );
    }

    @Bean
    public MilvusEmbeddingStore milvusEmbeddingStore() {
        return MilvusEmbeddingStore.builder()
                .host(props.host())
                .port(props.port())
                .collectionName(props.collection())
                .dimension(props.embeddingDimension())
                .retrieveEmbeddingsOnSearch(true)
                .build();
    }
}

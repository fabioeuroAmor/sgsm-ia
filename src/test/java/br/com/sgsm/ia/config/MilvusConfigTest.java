package br.com.sgsm.ia.config;

import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

class MilvusConfigTest {

    private final MilvusProperties props = new MilvusProperties("localhost", 19530, "sgsm_documentos", 1536);
    private final MilvusConfig config = new MilvusConfig(props);

    @Test
    void deveCriarClienteMilvusSemAbrirConexaoReal() {
        try (MockedConstruction<MilvusServiceClient> mocked = mockConstruction(MilvusServiceClient.class)) {
            var client = config.milvusServiceClient();

            assertThat(client).isNotNull();
            assertThat(mocked.constructed()).hasSize(1);
        }
    }

    @Test
    void deveCriarEmbeddingStoreComParametrosConfigurados() {
        try (MockedConstruction<MilvusEmbeddingStore> mocked = mockConstruction(MilvusEmbeddingStore.class)) {
            var store = config.milvusEmbeddingStore();

            assertThat(store).isNotNull();
            assertThat(mocked.constructed()).hasSize(1);
        }
    }
}

package br.com.sgsm.ia.service;

import br.com.sgsm.ia.config.IaProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MilvusIndexServiceTest {

    @Mock
    private MilvusEmbeddingStore store;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private JdbcTemplate jdbc;

    private MilvusIndexService service;

    @BeforeEach
    void setUp() {
        IaProperties props = new IaProperties("openai", 10,
                new IaProperties.RedisStreamProperties("stream", "group", "consumer"));
        service = new MilvusIndexService(store, embeddingModel, jdbc, props);
    }

    @Test
    void deveIndexarComSucesso() {
        Embedding embedding = Embedding.from(new float[] {0.1f, 0.2f});
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));
        when(store.add(eq(embedding), any(TextSegment.class))).thenReturn("milvus-id-1");

        service.upsert("PACIENTE", "id-1", "conteudo do paciente");

        verify(store).removeAll(any(Filter.class));
        verify(jdbc).update(contains("INSERT INTO crm.documento"),
                eq("PACIENTE"), eq("id-1"), eq("sgsm.paciente"), eq("conteudo do paciente"),
                eq("milvus-id-1"), eq("INDEXADO"));
    }

    @Test
    void deveRemoverVetorAnteriorAntesDeReindexar() {
        Embedding embedding = Embedding.from(new float[] {0.1f, 0.2f});
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));
        when(store.add(eq(embedding), any(TextSegment.class))).thenReturn("milvus-id-2");

        var inOrder = inOrder(store);

        service.upsert("PACIENTE", "id-1", "conteudo atualizado");

        inOrder.verify(store).removeAll(any(Filter.class));
        inOrder.verify(store).add(eq(embedding), any(TextSegment.class));
    }

    @Test
    void deveRegistrarErroERelancarExcecaoQuandoFalhaAoIndexar() {
        Embedding embedding = Embedding.from(new float[] {0.1f, 0.2f});
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));
        when(store.add(eq(embedding), any(TextSegment.class))).thenThrow(new RuntimeException("falha milvus"));

        assertThatThrownBy(() -> service.upsert("MEDICO", "id-2", "conteudo do medico"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("falha milvus");

        verify(jdbc).update(contains("status_indexacao = 'ERRO'"),
                eq("MEDICO"), eq("id-2"), eq("sgsm.medico"));
    }

    @Test
    void deveBuscarDocumentosSemelhantes() {
        Embedding queryEmbedding = Embedding.from(new float[] {0.3f, 0.4f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(queryEmbedding));

        var match = new EmbeddingMatch<>(0.95, "id-1", queryEmbedding, TextSegment.from("resultado"));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match)));

        List<EmbeddingMatch<TextSegment>> resultado = service.buscar("pergunta qualquer");

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).embedded().text()).isEqualTo("resultado");
    }

    @Test
    void naoDeveAplicarFiltroQuandoTipoNaoInformado() {
        Embedding queryEmbedding = Embedding.from(new float[] {0.3f, 0.4f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(queryEmbedding));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        service.buscar("pergunta qualquer");

        var captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(store).search(captor.capture());
        assertThat(captor.getValue().filter()).isNull();
    }

    @Test
    void deveAplicarFiltroDeTipoNaBuscaQuandoInformado() {
        Embedding queryEmbedding = Embedding.from(new float[] {0.3f, 0.4f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(queryEmbedding));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        service.buscar("qual foi o faturamento?", "analitico");

        var captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(store).search(captor.capture());
        Filter filtro = captor.getValue().filter();

        assertThat(filtro).isNotNull();
        assertThat(filtro.test(Metadata.from(Map.of("tipo", "ANALITICO", "referencia_id", "resumo-analitico"))))
                .isTrue();
        assertThat(filtro.test(Metadata.from(Map.of("tipo", "PACIENTE", "referencia_id", "1"))))
                .isFalse();
    }

    @Test
    void naoDeveAplicarFiltroQuandoTipoEmBranco() {
        Embedding queryEmbedding = Embedding.from(new float[] {0.3f, 0.4f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(queryEmbedding));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        service.buscar("pergunta qualquer", "   ");

        var captor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(store).search(captor.capture());
        assertThat(captor.getValue().filter()).isNull();
    }

    @Test
    void deveIndexarDocumentoAnaliticoComSucesso() {
        Embedding embedding = Embedding.from(new float[] {0.5f, 0.6f});
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));
        when(store.add(eq(embedding), any(TextSegment.class))).thenReturn("milvus-id-analitico");

        service.indexarAnalitico("resumo-analitico", "Resumo analítico do sistema.");

        verify(store).removeAll(any(Filter.class));
        verify(store).add(eq(embedding), any(TextSegment.class));
        verifyNoInteractions(jdbc);
    }

    @Test
    void deveApenasLogarAvisoQuandoFalhaAoIndexarDocumentoAnalitico() {
        Embedding embedding = Embedding.from(new float[] {0.5f, 0.6f});
        when(embeddingModel.embed(any(TextSegment.class))).thenReturn(Response.from(embedding));
        when(store.add(eq(embedding), any(TextSegment.class))).thenThrow(new RuntimeException("falha milvus"));

        assertThatCode(() -> service.indexarAnalitico("faturamento-mensal", "Faturamento mensal."))
                .doesNotThrowAnyException();
    }
}

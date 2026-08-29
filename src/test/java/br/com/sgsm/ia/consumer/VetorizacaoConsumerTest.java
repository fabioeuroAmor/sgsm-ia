package br.com.sgsm.ia.consumer;

import br.com.sgsm.ia.config.IaProperties;
import br.com.sgsm.ia.service.DocumentoBuilder;
import br.com.sgsm.ia.service.MilvusIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VetorizacaoConsumerTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private StreamOperations<String, Object, Object> streamOps;
    @Mock
    private DocumentoBuilder documentoBuilder;
    @Mock
    private MilvusIndexService milvusIndexService;

    private VetorizacaoConsumer consumer;

    @BeforeEach
    void setUp() {
        IaProperties props = new IaProperties("openai", 10,
                new IaProperties.RedisStreamProperties("sgsm:events:vetorizacao", "sgsm-ia-group", "sgsm-ia-consumer-1"));
        consumer = new VetorizacaoConsumer(redis, documentoBuilder, milvusIndexService, props);
        lenient().when(redis.opsForStream()).thenReturn(streamOps);
    }

    private static Map<Object, Object> valores(String tipo, String id) {
        Map<Object, Object> valores = new HashMap<>();
        valores.put("tipo", tipo);
        valores.put("id", id);
        return valores;
    }

    private static Map<Object, Object> valores(String tipo, String id, String operacao) {
        Map<Object, Object> valores = valores(tipo, id);
        valores.put("operacao", operacao);
        return valores;
    }

    @Test
    void deveCriarConsumerGroupComSucesso() {
        when(streamOps.createGroup(eq("sgsm:events:vetorizacao"), any(ReadOffset.class), eq("sgsm-ia-group")))
                .thenReturn("OK");

        consumer.inicializarConsumerGroup();

        verify(streamOps).createGroup(eq("sgsm:events:vetorizacao"), any(ReadOffset.class), eq("sgsm-ia-group"));
    }

    @Test
    void deveIgnorarErroQuandoConsumerGroupJaExiste() {
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RuntimeException("BUSYGROUP"));

        consumer.inicializarConsumerGroup();
    }

    @Test
    void naoDeveProcessarQuandoNaoHaMensagens() {
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(null);

        consumer.processar();

        verifyNoInteractions(documentoBuilder, milvusIndexService);
    }

    @Test
    void naoDeveProcessarQuandoListaVazia() {
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of());

        consumer.processar();

        verifyNoInteractions(documentoBuilder, milvusIndexService);
    }

    @Test
    void deveProcessarEConfirmarMensagemComSucesso() {
        MapRecord<String, Object, Object> mensagem = MapRecord
                .create("sgsm:events:vetorizacao", valores("PACIENTE", "id-1"))
                .withId(RecordId.of("1-1"));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(mensagem));
        when(documentoBuilder.construir("PACIENTE", "id-1")).thenReturn("texto do paciente");

        consumer.processar();

        verify(milvusIndexService).upsert("PACIENTE", "id-1", "texto do paciente");
        verify(streamOps).acknowledge("sgsm:events:vetorizacao", "sgsm-ia-group", RecordId.of("1-1"));
    }

    @Test
    void deveRemoverEmVezDeReindexarQuandoOperacaoAnonimizar() {
        MapRecord<String, Object, Object> mensagem = MapRecord
                .create("sgsm:events:vetorizacao", valores("PACIENTE", "id-3", "ANONIMIZAR"))
                .withId(RecordId.of("1-3"));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(mensagem));

        consumer.processar();

        verify(milvusIndexService).remover("PACIENTE", "id-3");
        verify(milvusIndexService, never()).upsert(anyString(), anyString(), anyString());
        verifyNoInteractions(documentoBuilder);
        verify(streamOps).acknowledge("sgsm:events:vetorizacao", "sgsm-ia-group", RecordId.of("1-3"));
    }

    @Test
    void naoDeveConfirmarMensagemQuandoProcessamentoFalha() {
        MapRecord<String, Object, Object> mensagem = MapRecord
                .create("sgsm:events:vetorizacao", valores("MEDICO", "id-2"))
                .withId(RecordId.of("1-2"));

        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(mensagem));
        when(documentoBuilder.construir("MEDICO", "id-2")).thenThrow(new RuntimeException("falha"));

        consumer.processar();

        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void deveTratarErroInesperadoNoLoopDeProcessamento() {
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenThrow(new RuntimeException("erro de conexão"));

        consumer.processar();

        verifyNoInteractions(documentoBuilder, milvusIndexService);
    }
}

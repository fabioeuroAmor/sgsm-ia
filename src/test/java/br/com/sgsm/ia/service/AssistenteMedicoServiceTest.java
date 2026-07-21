package br.com.sgsm.ia.service;

import br.com.sgsm.ia.guardrail.EscopoGuardrail;
import br.com.sgsm.ia.guardrail.SanitizacaoGuardrail;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistenteMedicoServiceTest {

    @Mock
    private MilvusIndexService milvusIndexService;
    @Mock
    private ChatLanguageModel chatModel;

    private AssistenteMedicoService service;

    @BeforeEach
    void setUp() {
        service = new AssistenteMedicoService(
                milvusIndexService, chatModel, new EscopoGuardrail(), new SanitizacaoGuardrail());
    }

    @Test
    void deveBloquearPerguntaForaDoEscopoSemChamarMilvusOuLlm() {
        String resposta = service.responder("Qual a previsão do tempo amanhã?");

        assertThat(resposta).contains("Só respondo perguntas relacionadas");
        verifyNoInteractions(milvusIndexService, chatModel);
    }

    @Test
    void deveResponderSemSanitizacaoQuandoNaoHaCpf() {
        when(milvusIndexService.buscar(anyString())).thenReturn(List.of(
                new EmbeddingMatch<>(0.9, "id1", null, TextSegment.from("Paciente sem restrições."))
        ));
        when(chatModel.generate(anyString())).thenReturn("O paciente está com as consultas em dia.");

        String resposta = service.responder("Qual o histórico do paciente?");

        assertThat(resposta).isEqualTo("O paciente está com as consultas em dia.");
    }

    @Test
    void deveIncluirResumoAnaliticoNoContextoQuandoRelevante() {
        when(milvusIndexService.buscar(anyString())).thenReturn(List.of(
                new EmbeddingMatch<>(0.9, "id1", null, TextSegment.from("Paciente sem restrições."))
        ));
        when(milvusIndexService.buscar(anyString(), eq("ANALITICO"))).thenReturn(List.of(
                new EmbeddingMatch<>(0.7, "id2", null, TextSegment.from("Receita total: R$ 10000.00."))
        ));
        when(chatModel.generate(anyString())).thenReturn("O faturamento foi de R$ 10000.00.");

        service.responder("Qual foi o faturamento do mês?");

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Paciente sem restrições.")
                .contains("Receita total: R$ 10000.00.");
    }

    @Test
    void naoDeveDuplicarTrechoQuandoResumoAnaliticoJaEstaNoTopKGeral() {
        var textoComum = new EmbeddingMatch<>(0.9, "id1", null, TextSegment.from("Receita total: R$ 10000.00."));
        when(milvusIndexService.buscar(anyString())).thenReturn(List.of(textoComum));
        when(milvusIndexService.buscar(anyString(), eq("ANALITICO"))).thenReturn(List.of(textoComum));
        when(chatModel.generate(anyString())).thenReturn("resposta");

        service.responder("Qual foi o faturamento do mês?");

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().split("Receita total: R\\$ 10000\\.00\\.", -1)).hasSize(2);
    }

    @Test
    void deveSanitizarCpfNaResposta() {
        when(milvusIndexService.buscar(anyString())).thenReturn(List.of(
                new EmbeddingMatch<>(0.9, "id1", null, TextSegment.from("Paciente cadastrado."))
        ));
        when(chatModel.generate(anyString())).thenReturn("CPF do paciente: 123.456.789-00");

        String resposta = service.responder("Qual o CPF do paciente?");

        assertThat(resposta).isEqualTo("CPF do paciente: ***.***.***-**");
    }
}

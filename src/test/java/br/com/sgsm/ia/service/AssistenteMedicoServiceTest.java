package br.com.sgsm.ia.service;

import br.com.sgsm.ia.guardrail.EscopoGuardrail;
import br.com.sgsm.ia.guardrail.SanitizacaoGuardrail;
import br.com.sgsm.ia.security.ContextoSeguranca;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistenteMedicoServiceTest {

    @Mock
    private RagAutorizacaoService ragAutorizacaoService;
    @Mock
    private ChatLanguageModel chatModel;
    @Mock
    private ContextoSeguranca contextoSeguranca;
    @Mock
    private KpiService kpiService;

    private AssistenteMedicoService service;

    @BeforeEach
    void setUp() {
        service = new AssistenteMedicoService(
                ragAutorizacaoService, chatModel, new EscopoGuardrail(), new SanitizacaoGuardrail(),
                contextoSeguranca, kpiService);
    }

    private static EmbeddingMatch<TextSegment> trecho(String texto) {
        return new EmbeddingMatch<>(0.9, texto, null, TextSegment.from(texto));
    }

    @Test
    void deveBloquearPerguntaForaDoEscopoSemChamarMilvusOuLlm() {
        String resposta = service.responder("Qual a previsão do tempo amanhã?");

        assertThat(resposta).contains("Só respondo perguntas relacionadas");
        verifyNoInteractions(ragAutorizacaoService, chatModel);
    }

    @Test
    void deveResponderSemSanitizacaoQuandoNaoHaCpf() {
        when(ragAutorizacaoService.buscar(anyString(), isNull())).thenReturn(List.of(trecho("Paciente sem restrições.")));
        when(chatModel.generate(anyString())).thenReturn("O paciente está com as consultas em dia.");

        String resposta = service.responder("Qual o histórico do paciente?");

        assertThat(resposta).isEqualTo("O paciente está com as consultas em dia.");
    }

    @Test
    void contextoDoPromptDeveVirSoDaBuscaAutorizada() {
        when(ragAutorizacaoService.buscar(anyString(), isNull())).thenReturn(List.of(trecho("Paciente sem restrições.")));
        when(ragAutorizacaoService.buscar(anyString(), eq("ANALITICO"))).thenReturn(List.of(trecho("Receita total: R$ 10000.00.")));
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
        var textoComum = trecho("Receita total: R$ 10000.00.");
        when(ragAutorizacaoService.buscar(anyString(), isNull())).thenReturn(List.of(textoComum));
        when(ragAutorizacaoService.buscar(anyString(), eq("ANALITICO"))).thenReturn(List.of(textoComum));
        when(chatModel.generate(anyString())).thenReturn("resposta");

        service.responder("Qual foi o faturamento do mês?");

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().split("Receita total: R\\$ 10000\\.00\\.", -1)).hasSize(2);
    }

    @Test
    void administradorDeveReceberResumoAnaliticoSoDasPropriasClinicas() {
        when(ragAutorizacaoService.buscar(anyString(), isNull())).thenReturn(List.of());
        when(contextoSeguranca.isAdminEstabelecimento()).thenReturn(true);
        when(kpiService.resumoDoUsuario()).thenReturn(Map.of("receita_total", 2500));
        when(chatModel.generate(anyString())).thenReturn("resposta");

        service.responder("Qual foi o faturamento do mês?");

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Resumo analítico dos estabelecimentos que você administra")
                .contains("receita_total: 2500");
    }

    @Test
    void demaisPapeisNaoRecebemResumoAnaliticoEscopado() {
        when(ragAutorizacaoService.buscar(anyString(), isNull())).thenReturn(List.of());
        when(chatModel.generate(anyString())).thenReturn("resposta");

        service.responder("Qual foi o faturamento do mês?");

        verify(kpiService, never()).resumoDoUsuario();
    }

    @Test
    void deveSanitizarCpfNaResposta() {
        when(ragAutorizacaoService.buscar(anyString(), isNull())).thenReturn(List.of(trecho("Paciente cadastrado.")));
        when(chatModel.generate(anyString())).thenReturn("CPF do paciente: 123.456.789-00");

        String resposta = service.responder("Qual o CPF do paciente?");

        assertThat(resposta).isEqualTo("CPF do paciente: ***.***.***-**");
    }
}

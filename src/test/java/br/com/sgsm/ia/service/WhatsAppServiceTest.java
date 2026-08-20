package br.com.sgsm.ia.service;

import br.com.sgsm.ia.dto.IntentWhatsApp;
import br.com.sgsm.ia.dto.MensagemHistorico;
import br.com.sgsm.ia.dto.WhatsAppClassificarRequest;
import br.com.sgsm.ia.guardrail.SanitizacaoGuardrail;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppServiceTest {

    @Mock
    private MilvusIndexService milvusIndexService;
    @Mock
    private ChatLanguageModel chatModel;

    private WhatsAppService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppService(
                milvusIndexService, chatModel, new SanitizacaoGuardrail(), new ObjectMapper());
    }

    private void semDocumentosNoMilvus() {
        when(milvusIndexService.buscar(anyString())).thenReturn(List.of());
    }

    private static String jsonConversar() {
        return "{\"intent\":\"CONVERSAR\",\"entidades\":{},\"respostaUsuario\":\"Olá! Como posso ajudar?\","
                + "\"requerConfirmacao\":false,\"dadosFaltantes\":[]}";
    }

    @Test
    void deveClassificarComSucessoDeduplicandoContextoDoMilvus() {
        when(milvusIndexService.buscar(anyString())).thenReturn(List.of(
                new EmbeddingMatch<>(0.9, "id1", null, TextSegment.from("Dr. João - Cardiologia")),
                new EmbeddingMatch<>(0.8, "id2", null, TextSegment.from("Dr. João - Cardiologia"))
        ));
        when(chatModel.generate(anyString())).thenReturn(
                "{\"intent\":\"AGENDAR\",\"entidades\":{\"especialidade\":\"Cardiologia\",\"medicoId\":\"med-1\"},"
                        + "\"respostaUsuario\":\"Vou agendar com o Dr. João.\",\"requerConfirmacao\":true,\"dadosFaltantes\":[]}");

        var request = new WhatsAppClassificarRequest("Quero marcar com o Dr. João", null, null, null);
        var resposta = service.classificar(request);

        assertThat(resposta.getIntent()).isEqualTo(IntentWhatsApp.AGENDAR);
        assertThat(resposta.getEntidades().getEspecialidade()).isEqualTo("Cardiologia");
        assertThat(resposta.getEntidades().getMedicoId()).isEqualTo("med-1");
        assertThat(resposta.isRequerConfirmacao()).isTrue();
        assertThat(resposta.getRespostaUsuario()).isEqualTo("Vou agendar com o Dr. João.");

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue().split("Dr\\. João - Cardiologia", -1)).hasSize(2);
    }

    @Test
    void deveUsarMensagemPadraoDeContextoQuandoNenhumDocumentoEncontrado() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn(jsonConversar());

        service.classificar(new WhatsAppClassificarRequest("Oi", null, null, null));

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("(nenhum dado relevante encontrado no sistema)");
    }

    @Test
    void deveUsarSemHistoricoQuandoHistoricoNulo() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn(jsonConversar());

        service.classificar(new WhatsAppClassificarRequest("Oi", null, null, null));

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("(sem histórico)");
    }

    @Test
    void deveUsarSemHistoricoQuandoHistoricoVazio() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn(jsonConversar());

        service.classificar(new WhatsAppClassificarRequest("Oi", List.of(), null, null));

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("(sem histórico)");
    }

    @Test
    void deveIncluirHistoricoDaConversaNoPrompt() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn(jsonConversar());

        var historico = List.of(
                new MensagemHistorico("usuario", "quero agendar"),
                new MensagemHistorico("assistente", "com qual médico?"));
        service.classificar(new WhatsAppClassificarRequest("Dr. João", historico, null, null));

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("usuario: quero agendar")
                .contains("assistente: com qual médico?");
    }

    @Test
    void devePreencherPerfilNaoIdentificadoQuandoPerfilNulo() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn(jsonConversar());

        service.classificar(new WhatsAppClassificarRequest("Oi", null, null, null));

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains("PERFIL DO USUÁRIO: NÃO IDENTIFICADO");
    }

    @Test
    void deveIncluirPerfilEConfirmacaoPendenteQuandoInformados() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn(jsonConversar());

        service.classificar(new WhatsAppClassificarRequest(
                "sim", null, "PACIENTE", "AGENDAR consulta cardiologia 10h"));

        var promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).generate(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("PERFIL DO USUÁRIO: PACIENTE")
                .contains("CONFIRMAÇÃO PENDENTE: AGENDAR consulta cardiologia 10h");
    }

    @Test
    void deveSanitizarCpfNaRespostaDoLlm() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn(
                "{\"intent\":\"CONSULTAR\",\"entidades\":{},\"respostaUsuario\":\"Seu CPF cadastrado é 123.456.789-00\","
                        + "\"requerConfirmacao\":false,\"dadosFaltantes\":[]}");

        var resposta = service.classificar(new WhatsAppClassificarRequest("qual meu cpf", null, null, null));

        assertThat(resposta.getRespostaUsuario()).isEqualTo("Seu CPF cadastrado é ***.***.***-**");
    }

    @Test
    void deveRemoverBlocosMarkdownAntesDeFazerParseDoJson() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn("```json\n" + jsonConversar() + "\n```");

        var resposta = service.classificar(new WhatsAppClassificarRequest("oi", null, null, null));

        assertThat(resposta.getIntent()).isEqualTo(IntentWhatsApp.CONVERSAR);
    }

    @Test
    void deveExtrairJsonQuandoHouverTextoAntesEDepois() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn("Aqui está a resposta: " + jsonConversar() + " Obrigado!");

        var resposta = service.classificar(new WhatsAppClassificarRequest("oi", null, null, null));

        assertThat(resposta.getIntent()).isEqualTo(IntentWhatsApp.CONVERSAR);
    }

    @Test
    void deveRetornarFallbackQuandoRespostaNaoForJsonValido() {
        semDocumentosNoMilvus();
        when(chatModel.generate(anyString())).thenReturn("desculpe, não posso ajudar com isso");

        var resposta = service.classificar(new WhatsAppClassificarRequest("???", null, null, null));

        assertThat(resposta.getIntent()).isEqualTo(IntentWhatsApp.CONVERSAR);
        assertThat(resposta.isRequerConfirmacao()).isFalse();
        assertThat(resposta.getDadosFaltantes()).isEmpty();
        assertThat(resposta.getRespostaUsuario()).contains("Desculpe, não entendi bem");
    }
}

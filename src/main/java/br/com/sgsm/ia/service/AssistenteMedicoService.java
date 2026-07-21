package br.com.sgsm.ia.service;

import br.com.sgsm.ia.guardrail.EscopoGuardrail;
import br.com.sgsm.ia.guardrail.SanitizacaoGuardrail;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AssistenteMedicoService {

    private static final String SYSTEM_PROMPT = """
            Você é um assistente médico do sistema SGSM.
            Responda APENAS com base nos registros fornecidos no CONTEXTO abaixo.
            Nunca revele CPF completo, senhas ou tokens de autenticação.
            Se não encontrar a informação, diga que não sabe.
            Responda em português brasileiro.
            """;

    private final MilvusIndexService milvusIndexService;
    private final ChatLanguageModel chatModel;
    private final EscopoGuardrail escopoGuardrail;
    private final SanitizacaoGuardrail sanitizacaoGuardrail;

    public AssistenteMedicoService(MilvusIndexService milvusIndexService,
                                   ChatLanguageModel chatModel,
                                   EscopoGuardrail escopoGuardrail,
                                   SanitizacaoGuardrail sanitizacaoGuardrail) {
        this.milvusIndexService = milvusIndexService;
        this.chatModel = chatModel;
        this.escopoGuardrail = escopoGuardrail;
        this.sanitizacaoGuardrail = sanitizacaoGuardrail;
    }

    public String responder(String pergunta) {
        // Camada 1: InputGuardrail — valida escopo antes de chamar Milvus ou LLM
        var inputResult = escopoGuardrail.validate(
                dev.langchain4j.data.message.UserMessage.from(pergunta));
        if (!inputResult.isSuccess()) {
            return inputResult.failureMessage();
        }

        // Camada 2: Busca semântica no Milvus — top-K geral + busca dedicada do resumo
        // analítico (tipo=ANALITICO), que senão concorreria em desvantagem no top-K geral
        // contra o volume muito maior de documentos de pacientes/médicos/agendamentos
        List<EmbeddingMatch<TextSegment>> matches = milvusIndexService.buscar(pergunta);
        List<EmbeddingMatch<TextSegment>> resumoAnalitico = milvusIndexService.buscar(pergunta, "ANALITICO");

        String contexto = Stream.concat(matches.stream(), resumoAnalitico.stream())
                .map(m -> m.embedded().text())
                .distinct()
                .collect(Collectors.joining("\n---\n"));

        // Camada 3: Montagem do prompt com contexto isolado
        String promptTexto = SYSTEM_PROMPT + "\n\nCONTEXTO:\n" + contexto + "\n\nPERGUNTA: " + pergunta;

        // Camada 4: Chamada ao LLM
        String resposta = chatModel.generate(promptTexto);

        // Camada 5: OutputGuardrail — sanitização
        var outputResult = sanitizacaoGuardrail.validate(
                dev.langchain4j.data.message.AiMessage.from(resposta));
        return outputResult.isSuccess() ? resposta : outputResult.successText();
    }
}

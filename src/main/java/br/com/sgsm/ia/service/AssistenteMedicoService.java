package br.com.sgsm.ia.service;

import br.com.sgsm.ia.guardrail.EscopoGuardrail;
import br.com.sgsm.ia.guardrail.SanitizacaoGuardrail;
import br.com.sgsm.ia.security.ContextoSeguranca;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AssistenteMedicoService {

    private static final Logger log = LoggerFactory.getLogger(AssistenteMedicoService.class);

    private static final String SYSTEM_PROMPT = """
            Você é um assistente médico do sistema SGSM.
            Responda APENAS com base nos registros fornecidos no CONTEXTO abaixo.
            Nunca revele CPF completo, senhas ou tokens de autenticação.
            Se não encontrar a informação, diga que não sabe.
            Responda em português brasileiro.
            """;

    private final RagAutorizacaoService ragAutorizacaoService;
    private final ChatLanguageModel chatModel;
    private final EscopoGuardrail escopoGuardrail;
    private final SanitizacaoGuardrail sanitizacaoGuardrail;
    private final ContextoSeguranca contextoSeguranca;
    private final KpiService kpiService;

    public AssistenteMedicoService(RagAutorizacaoService ragAutorizacaoService,
                                   ChatLanguageModel chatModel,
                                   EscopoGuardrail escopoGuardrail,
                                   SanitizacaoGuardrail sanitizacaoGuardrail,
                                   ContextoSeguranca contextoSeguranca,
                                   KpiService kpiService) {
        this.ragAutorizacaoService = ragAutorizacaoService;
        this.chatModel = chatModel;
        this.escopoGuardrail = escopoGuardrail;
        this.sanitizacaoGuardrail = sanitizacaoGuardrail;
        this.contextoSeguranca = contextoSeguranca;
        this.kpiService = kpiService;
    }

    public String responder(String pergunta) {
        // Camada 1: InputGuardrail — valida escopo antes de chamar Milvus ou LLM
        var inputResult = escopoGuardrail.validate(
                dev.langchain4j.data.message.UserMessage.from(pergunta));
        if (!inputResult.isSuccess()) {
            return inputResult.failureMessage();
        }

        // Camada 2: Busca semântica no Milvus filtrada pelo que o usuário pode ver — top-K geral
        // + busca dedicada do resumo analítico (tipo=ANALITICO, só DESENVOLVEDOR), que senão
        // concorreria em desvantagem no top-K geral contra o volume muito maior de documentos
        List<EmbeddingMatch<TextSegment>> matches = ragAutorizacaoService.buscar(pergunta, null);
        List<EmbeddingMatch<TextSegment>> resumoAnalitico = ragAutorizacaoService.buscar(pergunta, "ANALITICO");

        Stream<String> trechos = Stream.concat(matches.stream(), resumoAnalitico.stream())
                .map(m -> m.embedded().text());
        // O ANALITICO do Milvus é global; o administrador recebe o resumo só das próprias clínicas
        if (contextoSeguranca.isAdminEstabelecimento()) {
            trechos = Stream.concat(trechos, Stream.of(resumoAnaliticoDoUsuario()));
        }
        String contexto = trechos
                .distinct()
                .collect(Collectors.joining("\n---\n"));

        // Camada 3: Montagem do prompt com contexto isolado
        String promptTexto = SYSTEM_PROMPT + "\n\nCONTEXTO:\n" + contexto + "\n\nPERGUNTA: " + pergunta;

        // Camada 4: Chamada ao LLM
        log.debug("Prompt enviado ao LLM (pergunta='{}'):\n{}", pergunta, promptTexto);
        String resposta = chatModel.generate(promptTexto);
        log.debug("Resposta bruta do LLM: {}", resposta);

        // Camada 5: OutputGuardrail — sanitização
        var outputResult = sanitizacaoGuardrail.validate(
                dev.langchain4j.data.message.AiMessage.from(resposta));
        return outputResult.isSuccess() ? resposta : outputResult.successText();
    }

    private String resumoAnaliticoDoUsuario() {
        String corpo = kpiService.resumoDoUsuario().entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(", "));
        return "Resumo analítico dos estabelecimentos que você administra. " + corpo + ".";
    }
}

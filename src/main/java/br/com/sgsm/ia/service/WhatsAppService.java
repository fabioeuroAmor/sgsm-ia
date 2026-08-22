package br.com.sgsm.ia.service;

import br.com.sgsm.ia.dto.EntidadesWhatsApp;
import br.com.sgsm.ia.dto.IntentWhatsApp;
import br.com.sgsm.ia.dto.MensagemHistorico;
import br.com.sgsm.ia.dto.WhatsAppClassificarRequest;
import br.com.sgsm.ia.dto.WhatsAppClassificarResponse;
import br.com.sgsm.ia.guardrail.SanitizacaoGuardrail;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);

    // System prompt — instrui o LLM a retornar JSON estrito
    private static final String SYSTEM_PROMPT = """
            Você é o assistente virtual do SGSM no WhatsApp.
            Seu papel: identificar a intenção do usuário e extrair informações relevantes.

            REGRAS ABSOLUTAS:
            1. Retorne APENAS um objeto JSON válido. NADA além do JSON — sem explicações, sem markdown.
            2. Não use blocos de código (```). Apenas JSON puro começando com { e terminando com }.
            3. respostaUsuario: máximo 4 linhas, use *asterisco* para negrito (formato WhatsApp).
            4. Seja direto e amigável.

            INTENÇÕES POSSÍVEIS:
            - CONSULTAR: quer informações (médicos, horários, preços, histórico clínico)
            - AGENDAR: quer marcar consulta ou exame
            - CANCELAR: quer cancelar agendamento existente
            - CADASTRAR: quer se registrar como paciente
            - ATIVAR_FUNC: funcionário quer ativar sua conta
            - CONFIRMAR: respondeu sim/confirmo/ok/yes a confirmação pendente
            - RECUSAR: respondeu não/cancela/nao/no a confirmação pendente
            - CONVERSAR: saudação, agradecimento ou mensagem genérica
            - AUTENTICAR: precisa se identificar ou logar

            FORMATO DE SAÍDA (JSON exato — não altere os nomes dos campos):
            {
              "intent": "<INTENCAO>",
              "entidades": {
                "especialidade": null,
                "data": null,
                "hora": null,
                "nomeMedico": null,
                "nomePaciente": null,
                "medicoId": null,
                "servicoMedicoId": null,
                "agendamentoId": null,
                "email": null,
                "cpf": null,
                "dataNascimento": null,
                "nomeCompleto": null
              },
              "respostaUsuario": "<texto>",
              "requerConfirmacao": false,
              "dadosFaltantes": []
            }

            REGRAS PARA dadosFaltantes:
            - intent=AGENDAR sem todos os dados: liste campos faltantes em dadosFaltantes
            - intent=CADASTRAR sem todos os dados: liste campos faltantes
            - Quando todos dados presentes E ação pode ser executada: requerConfirmacao=true, dadosFaltantes=[]

            REGRA DE IDs (IMPORTANTE):
            - Se o CONTEXTO contém dados de médico mencionado pelo usuário, extraia o "referencia_id" do contexto e coloque em medicoId.
            - Se o CONTEXTO contém um serviço médico relevante, coloque seu ID em servicoMedicoId.
            - Se o CONTEXTO contém agendamento a cancelar, coloque seu ID em agendamentoId.
            """;

    private final MilvusIndexService milvusIndexService;
    private final ChatLanguageModel chatModel;
    private final SanitizacaoGuardrail sanitizacaoGuardrail;
    private final ObjectMapper objectMapper;

    public WhatsAppService(MilvusIndexService milvusIndexService,
                           ChatLanguageModel chatModel,
                           SanitizacaoGuardrail sanitizacaoGuardrail,
                           ObjectMapper objectMapper) {
        this.milvusIndexService = milvusIndexService;
        this.chatModel = chatModel;
        this.sanitizacaoGuardrail = sanitizacaoGuardrail;
        this.objectMapper = objectMapper;
    }

    public WhatsAppClassificarResponse classificar(WhatsAppClassificarRequest request) {
        // 1. Busca semântica no Milvus
        List<EmbeddingMatch<TextSegment>> matches = milvusIndexService.buscar(request.mensagem());
        String contexto = matches.stream()
                .map(m -> m.embedded().text())
                .distinct()
                .collect(Collectors.joining("\n---\n"));
        if (contexto.isBlank()) {
            contexto = "(nenhum dado relevante encontrado no sistema)";
        }

        // 2. Monta histórico
        String historico = (request.historico() == null || request.historico().isEmpty())
                ? "(sem histórico)"
                : request.historico().stream()
                        .map(m -> m.papel() + ": " + m.texto())
                        .collect(Collectors.joining("\n"));

        // 3. Monta prompt completo
        String prompt = SYSTEM_PROMPT
                + "\n\nCONTEXTO DO SISTEMA:\n" + contexto
                + "\n\nHISTÓRICO DA CONVERSA:\n" + historico
                + "\n\nPERFIL DO USUÁRIO: " + (request.perfil() != null ? request.perfil() : "NÃO IDENTIFICADO")
                + "\n\nCONFIRMAÇÃO PENDENTE: " + (request.confirmacaoPendente() != null ? request.confirmacaoPendente() : "nenhuma")
                + "\n\nMENSAGEM DO USUÁRIO: " + request.mensagem();

        // 4. Chama LLM
        log.debug("WhatsApp classificar - mensagem: '{}'", request.mensagem());
        String respostaLLM = chatModel.generate(prompt);
        log.debug("WhatsApp LLM resposta bruta: {}", respostaLLM);

        // 5. Sanitiza (remove CPF da resposta se o LLM repetir dados sensíveis)
        var sanitizada = sanitizacaoGuardrail.validate(AiMessage.from(respostaLLM));
        String textoFinal = sanitizada.isSuccess() ? respostaLLM : sanitizada.successText();

        // 6. Parse JSON com fallback
        return parseResposta(textoFinal, request.mensagem());
    }

    private WhatsAppClassificarResponse parseResposta(String texto, String mensagemOriginal) {
        try {
            // Remove markdown code blocks se o LLM insistir em adicioná-los
            String json = texto.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json\\s*|```\\s*", "").trim();
            }
            // Extrai o JSON se houver texto antes ou depois
            int inicio = json.indexOf('{');
            int fim = json.lastIndexOf('}');
            if (inicio >= 0 && fim > inicio) {
                json = json.substring(inicio, fim + 1);
            }
            return objectMapper.readValue(json, WhatsAppClassificarResponse.class);
        } catch (Exception e) {
            log.warn("Falha ao parsear JSON do LLM para mensagem '{}': {}", mensagemOriginal, e.getMessage());
            return fallback();
        }
    }

    private WhatsAppClassificarResponse fallback() {
        return WhatsAppClassificarResponse.builder()
                .intent(IntentWhatsApp.CONVERSAR)
                .entidades(new EntidadesWhatsApp())
                .respostaUsuario("Desculpe, não entendi bem. Posso ajudar com agendamentos, informações sobre médicos, cadastro ou dúvidas sobre o sistema SGSM.")
                .requerConfirmacao(false)
                .dadosFaltantes(List.of())
                .build();
    }
}

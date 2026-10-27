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
            - Cada documento do CONTEXTO começa com [tipo=... referencia_id=...]: o referencia_id é o ID desse registro.
            - Médico mencionado pelo usuário: use o referencia_id do documento tipo=MEDICO em medicoId.
            - Serviço médico que o usuário quer agendar: use o referencia_id do documento tipo=SERVICO_MEDICO em servicoMedicoId.
            - Agendamento a cancelar: use o referencia_id do documento tipo=AGENDAMENTO em agendamentoId.
            - Nunca invente IDs nem copie IDs de outro tipo de documento.
            - Nunca mostre IDs (referencia_id) na respostaUsuario: eles são só para os campos de entidades.

            REGRA DE FIDELIDADE (IMPORTANTE):
            - Cite apenas médicos, serviços, exames, preços e horários que aparecem no CONTEXTO DO SISTEMA.
            - Nunca complete com serviços "comuns" de clínica (consultas, exames de imagem, laboratório etc.) que não estejam no CONTEXTO.
            - Se o CONTEXTO não trouxer o que foi perguntado, diga que não encontrou essa informação cadastrada e ofereça ajuda com outra coisa.

            REGRA DE AUTENTICAÇÃO (IMPORTANTE):
            - PERFIL DO USUÁRIO = "NÃO IDENTIFICADO" significa que o usuário ainda não confirmou sua identidade.
            - Classifique a intenção real (AGENDAR, CANCELAR, etc.) mesmo com PERFIL "NÃO IDENTIFICADO". NUNCA troque a intenção para AUTENTICAR só por causa disso — a verificação de identidade é tratada automaticamente pelo sistema depois da classificação.
            - Se PERFIL for "NÃO IDENTIFICADO" e a intenção for AGENDAR ou CANCELAR, ajuste respostaUsuario para pedir o e-mail cadastrado antes de prosseguir.
            """;

    private final RagAutorizacaoService ragAutorizacaoService;
    private final ChatLanguageModel chatModel;
    private final SanitizacaoGuardrail sanitizacaoGuardrail;
    private final ObjectMapper objectMapper;

    public WhatsAppService(RagAutorizacaoService ragAutorizacaoService,
                           ChatLanguageModel chatModel,
                           SanitizacaoGuardrail sanitizacaoGuardrail,
                           ObjectMapper objectMapper) {
        this.ragAutorizacaoService = ragAutorizacaoService;
        this.chatModel = chatModel;
        this.sanitizacaoGuardrail = sanitizacaoGuardrail;
        this.objectMapper = objectMapper;
    }

    public WhatsAppClassificarResponse classificar(WhatsAppClassificarRequest request) {
        // 1. Busca semântica no Milvus — só catálogo público + agendamentos do próprio paciente
        List<EmbeddingMatch<TextSegment>> matches = request.estabelecimentoId() == null
                ? ragAutorizacaoService.buscarParaWhatsApp(request.mensagem())
                : ragAutorizacaoService.buscarParaWhatsApp(request.mensagem(), request.estabelecimentoId());
        String contexto = matches.stream()
                .map(WhatsAppService::documentoComReferencia)
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
                + clinicaDoAtendimento(request)
                + semServicosNoContexto(matches)
                + "\n\nPERFIL DO USUÁRIO: " + (request.perfil() != null ? request.perfil() : "NÃO IDENTIFICADO")
                + "\n\nCONFIRMAÇÃO PENDENTE: " + (request.confirmacaoPendente() != null ? request.confirmacaoPendente() : "nenhuma")
                + "\n\nMENSAGEM DO USUÁRIO: " + request.mensagem();

        // 4. Chama LLM
        log.debug("WhatsApp classificar - mensagem: '{}'", request.mensagem());
        String respostaLLM = chatModel.generate(prompt);
        log.debug("WhatsApp LLM resposta bruta: {}", respostaLLM);

        // 5. Parse JSON com fallback
        WhatsAppClassificarResponse resposta = parseResposta(respostaLLM, request.mensagem());

        // 6. Reforço determinístico: não deixa AGENDAR/CANCELAR prosseguir sem perfil identificado,
        // independente do LLM ter seguido a instrução do prompt (defesa em profundidade)
        if ((resposta.getIntent() == IntentWhatsApp.AGENDAR || resposta.getIntent() == IntentWhatsApp.CANCELAR)
                && request.perfil() == null) {
            resposta.setRespostaUsuario("Para "
                    + (resposta.getIntent() == IntentWhatsApp.AGENDAR ? "agendar" : "cancelar")
                    + ", primeiro preciso confirmar sua identidade. Digite seu e-mail cadastrado.");
        }

        // 6b. Reforço determinístico: AGENDAR só vai para confirmação com serviço, data e hora extraídos.
        // O LLM às vezes marca requerConfirmacao sem o servicoMedicoId — o bot então pedia "Sim" ao
        // paciente e o agendamento falhava no sgsm (rodada 8 do QA de conflito de agendamento)
        // (sem perfil identificado vale a mensagem de identificação do passo 6, não esta)
        if (resposta.getIntent() == IntentWhatsApp.AGENDAR && resposta.isRequerConfirmacao() && request.perfil() != null) {
            List<String> faltando = camposDeAgendamentoFaltando(resposta.getEntidades());
            if (!faltando.isEmpty()) {
                resposta.setRequerConfirmacao(false);
                resposta.setDadosFaltantes(faltando);
                resposta.setRespostaUsuario(perguntaPeloQueFalta(faltando));
            }
        }

        // 7. Sanitiza só o texto de conversa (nunca as entidades estruturadas, ex.: cpf/email)
        if (resposta.getRespostaUsuario() != null) {
            var sanitizada = sanitizacaoGuardrail.validate(AiMessage.from(resposta.getRespostaUsuario()));
            if (!sanitizada.isSuccess()) {
                resposta.setRespostaUsuario(sanitizada.successText());
            }
        }

        return resposta;
    }

    // Prefixa o documento com tipo e referencia_id (vêm do metadado do Milvus): sem isso o LLM não tinha
    // como preencher servicoMedicoId/medicoId e o agendamento pelo WhatsApp nunca chegava à confirmação
    static String documentoComReferencia(EmbeddingMatch<TextSegment> m) {
        var metadata = m.embedded().metadata();
        String tipo = metadata == null ? null : metadata.getString("tipo");
        String referencia = metadata == null ? null : metadata.getString("referencia_id");
        if (tipo == null || referencia == null) {
            return m.embedded().text();
        }
        return "[tipo=" + tipo + " referencia_id=" + referencia + "] " + m.embedded().text();
    }

    // WhatsApp de uma clínica: o atendimento é só dela (o contexto já vem filtrado pela clínica)
    static String clinicaDoAtendimento(WhatsAppClassificarRequest request) {
        if (request.estabelecimentoId() == null) {
            return "";
        }
        String nome = request.estabelecimentoNome() != null ? request.estabelecimentoNome() : "esta clínica";
        return "\n\nCLÍNICA DESTE ATENDIMENTO: " + nome
                + " — você é o assistente dela. Ofereça apenas médicos, serviços e horários desta clínica; "
                + "se pedirem outra clínica, explique que este número atende só " + nome + ".";
    }

    // QA WA-B06: no número de uma clínica sem serviço ativo, o LLM inventava "consultas, exames de imagem…".
    // Aviso explícito quando nenhum serviço veio do Milvus (a regra de fidelidade sozinha não bastou)
    static String semServicosNoContexto(List<EmbeddingMatch<TextSegment>> matches) {
        boolean temServico = matches.stream().anyMatch(m -> m.embedded().metadata() != null
                && "SERVICO_MEDICO".equals(m.embedded().metadata().getString("tipo")));
        return temServico ? ""
                : "\n\nSERVIÇOS NO CONTEXTO: nenhum. Não cite nenhum serviço, exame ou procedimento; se perguntarem, "
                        + "diga que não encontrou serviços cadastrados e que a equipe da clínica pode informar.";
    }

    static List<String> camposDeAgendamentoFaltando(EntidadesWhatsApp e) {
        List<String> faltando = new java.util.ArrayList<>();
        if (e == null || e.getServicoMedicoId() == null || e.getServicoMedicoId().isBlank()) faltando.add("servicoMedicoId");
        if (e == null || e.getData() == null || e.getData().isBlank()) faltando.add("data");
        if (e == null || e.getHora() == null || e.getHora().isBlank()) faltando.add("hora");
        return faltando;
    }

    static String perguntaPeloQueFalta(List<String> faltando) {
        if (faltando.contains("servicoMedicoId")) {
            return "Para agendar, preciso saber qual consulta ou serviço você deseja e com qual médico. "
                    + "Pode me dizer o nome do serviço?";
        }
        return "Para agendar, me informe a data e o horário desejados (ex.: 21/10/2026 às 10:00).";
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

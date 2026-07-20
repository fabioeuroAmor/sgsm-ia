package br.com.sgsm.ia.guardrail;

import dev.langchain4j.data.message.AiMessage;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class SanitizacaoGuardrail implements OutputGuardrail {

    // CPF nos formatos: 000.000.000-00 ou 00000000000
    private static final Pattern CPF = Pattern.compile("\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}");

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String resposta = responseFromLLM.text();
        if (CPF.matcher(resposta).find()) {
            String sanitizada = CPF.matcher(resposta).replaceAll("***.***.***-**");
            return successWith(sanitizada);
        }
        return success();
    }
}

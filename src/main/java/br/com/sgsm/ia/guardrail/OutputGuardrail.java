package br.com.sgsm.ia.guardrail;

import dev.langchain4j.data.message.AiMessage;

public interface OutputGuardrail {

    OutputGuardrailResult validate(AiMessage responseFromLLM);

    default OutputGuardrailResult success() {
        return OutputGuardrailResult.success();
    }

    default OutputGuardrailResult successWith(String text) {
        return OutputGuardrailResult.successWith(text);
    }
}

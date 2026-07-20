package br.com.sgsm.ia.guardrail;

import dev.langchain4j.data.message.UserMessage;

public interface InputGuardrail {

    InputGuardrailResult validate(UserMessage userMessage);

    default InputGuardrailResult success() {
        return InputGuardrailResult.success();
    }

    default InputGuardrailResult failure(String message) {
        return InputGuardrailResult.failure(message);
    }
}

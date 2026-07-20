package br.com.sgsm.ia.guardrail;

public final class OutputGuardrailResult {

    private static final OutputGuardrailResult SUCCESS = new OutputGuardrailResult(true, null);

    private final boolean success;
    private final String successText;

    private OutputGuardrailResult(boolean success, String successText) {
        this.success = success;
        this.successText = successText;
    }

    static OutputGuardrailResult success() {
        return SUCCESS;
    }

    // isSuccess() vem false aqui de proposito: sinaliza ao chamador que a resposta
    // original foi reescrita e deve usar successText() no lugar dela.
    static OutputGuardrailResult successWith(String text) {
        return new OutputGuardrailResult(false, text);
    }

    public boolean isSuccess() {
        return success;
    }

    public String successText() {
        return successText;
    }
}

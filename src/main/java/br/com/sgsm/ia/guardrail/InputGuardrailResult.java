package br.com.sgsm.ia.guardrail;

public final class InputGuardrailResult {

    private static final InputGuardrailResult SUCCESS = new InputGuardrailResult(true, null);

    private final boolean success;
    private final String failureMessage;

    private InputGuardrailResult(boolean success, String failureMessage) {
        this.success = success;
        this.failureMessage = failureMessage;
    }

    static InputGuardrailResult success() {
        return SUCCESS;
    }

    static InputGuardrailResult failure(String message) {
        return new InputGuardrailResult(false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String failureMessage() {
        return failureMessage;
    }
}

package br.com.sgsm.ia.guardrail;

import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizacaoGuardrailTest {

    private final SanitizacaoGuardrail guardrail = new SanitizacaoGuardrail();

    @Test
    void devePermanecerInalteradoQuandoNaoHaCpf() {
        var resultado = guardrail.validate(AiMessage.from("O paciente possui 3 consultas concluídas."));

        assertThat(resultado.isSuccess()).isTrue();
        assertThat(resultado.successText()).isNull();
    }

    @Test
    void deveMascararCpfComPontuacao() {
        var resultado = guardrail.validate(AiMessage.from("CPF do paciente: 123.456.789-00"));

        assertThat(resultado.isSuccess()).isFalse();
        assertThat(resultado.successText()).isEqualTo("CPF do paciente: ***.***.***-**");
    }

    @Test
    void deveMascararCpfSemPontuacao() {
        var resultado = guardrail.validate(AiMessage.from("CPF: 12345678900"));

        assertThat(resultado.isSuccess()).isFalse();
        assertThat(resultado.successText()).contains("***.***.***-**");
    }
}

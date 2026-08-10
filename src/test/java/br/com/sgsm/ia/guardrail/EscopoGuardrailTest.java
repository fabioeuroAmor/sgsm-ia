package br.com.sgsm.ia.guardrail;

import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EscopoGuardrailTest {

    private final EscopoGuardrail guardrail = new EscopoGuardrail();

    @Test
    void deveAceitarPerguntaDentroDoEscopo() {
        var resultado = guardrail.validate(UserMessage.from("Qual o histórico do paciente João?"));

        assertThat(resultado.isSuccess()).isTrue();
        assertThat(resultado.failureMessage()).isNull();
    }

    @Test
    void deveRejeitarPerguntaForaDoEscopo() {
        var resultado = guardrail.validate(UserMessage.from("Qual a previsão do tempo amanhã?"));

        assertThat(resultado.isSuccess()).isFalse();
        assertThat(resultado.failureMessage())
                .contains("Só respondo perguntas relacionadas");
    }

    @Test
    void deveAceitarComTermoAcentuado() {
        var resultado = guardrail.validate(UserMessage.from("Preciso do meu histórico médico"));

        assertThat(resultado.isSuccess()).isTrue();
    }

    @Test
    void deveAceitarPerguntaFinanceiraDoCrmAnalitico() {
        var resultado = guardrail.validate(UserMessage.from("Qual foi o faturamento do mês?"));

        assertThat(resultado.isSuccess()).isTrue();
    }

    @Test
    void deveAceitarPerguntaSobreTaxaDeConversao() {
        var resultado = guardrail.validate(UserMessage.from("Qual a taxa de conversão dos leads?"));

        assertThat(resultado.isSuccess()).isTrue();
    }

    @Test
    void deveAceitarPerguntaSobreChurn() {
        var resultado = guardrail.validate(UserMessage.from("Quem está em risco de churn este mês?"));

        assertThat(resultado.isSuccess()).isTrue();
    }

    @Test
    void deveAceitarPerguntaComVerboFaturar() {
        var resultado = guardrail.validate(UserMessage.from("Quanto o Dr. Fabio Amorim faturou?"));

        assertThat(resultado.isSuccess()).isTrue();
    }
}

package br.com.sgsm.ia.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EscopoInvalidoExceptionTest {

    @Test
    void devePreservarMensagemDaExcecao() {
        var ex = new EscopoInvalidoException("Pergunta fora do escopo do SGSM");

        assertThat(ex.getMessage()).isEqualTo("Pergunta fora do escopo do SGSM");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}

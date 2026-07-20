package br.com.sgsm.ia.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecursoNaoEncontradoExceptionTest {

    @Test
    void devePreservarMensagemDaExcecao() {
        var ex = new RecursoNaoEncontradoException("Paciente não encontrado: 123");

        assertThat(ex.getMessage()).isEqualTo("Paciente não encontrado: 123");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}

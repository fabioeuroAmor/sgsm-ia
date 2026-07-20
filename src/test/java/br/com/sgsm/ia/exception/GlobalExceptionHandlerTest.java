package br.com.sgsm.ia.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private HttpServletRequest request;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @BeforeEach
    void setUp() {
        when(request.getRequestURI()).thenReturn("/ia/chat");
    }

    @Test
    void deveTratarEscopoInvalido() {
        var response = handler.handleEscopoInvalido(new EscopoInvalidoException("fora do escopo"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().getTitle()).isEqualTo("Pergunta fora do escopo");
        assertThat(response.getBody().getDetail()).isEqualTo("fora do escopo");
        assertThat(response.getBody().getType().toString()).contains("escopo-invalido");
        assertThat(response.getBody().getInstance().toString()).isEqualTo("/ia/chat");
    }

    @Test
    void deveTratarRecursoNaoEncontrado() {
        var response = handler.handleNaoEncontrado(new RecursoNaoEncontradoException("paciente não encontrado"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().getTitle()).isEqualTo("Recurso não encontrado");
        assertThat(response.getBody().getDetail()).isEqualTo("paciente não encontrado");
    }

    @Test
    void deveTratarArgumentoInvalido() {
        var response = handler.handleArgumentoInvalido(new IllegalArgumentException("argumento ruim"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getTitle()).isEqualTo("Requisição inválida");
    }

    @Test
    void deveTratarErroInternoGenerico() {
        var response = handler.handleErroInterno(new RuntimeException("qualquer coisa"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getTitle()).isEqualTo("Erro interno");
        assertThat(response.getBody().getDetail()).isEqualTo("Erro interno. Tente novamente mais tarde.");
    }
}

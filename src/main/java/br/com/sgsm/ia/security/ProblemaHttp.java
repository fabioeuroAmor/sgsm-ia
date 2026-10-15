package br.com.sgsm.ia.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Corpo de erro no formato ProblemDetail (o mesmo do GlobalExceptionHandler) para respostas
// escritas direto na cadeia de filtros do Spring Security, antes de chegar ao controller
final class ProblemaHttp {

    private ProblemaHttp() {
    }

    static void escrever(HttpServletResponse response, int status, String tipo, String titulo,
                         String detalhe, String instancia) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"https://sgsm.com.br/erros/%s","title":"%s","status":%d,"detail":"%s","instance":"%s"}"""
                .formatted(tipo, titulo, status, detalhe, instancia.replace("\\", "").replace("\"", "")));
    }
}

package br.com.sgsm.ia.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

// 401/403 da cadeia do Spring Security com o mesmo corpo ProblemDetail do GlobalExceptionHandler
public final class SecurityErrorHandlers {

    private SecurityErrorHandlers() {
    }

    public static AuthenticationEntryPoint naoAutenticado() {
        return (request, response, ex) -> ProblemaHttp.escrever(response, HttpServletResponse.SC_UNAUTHORIZED,
                "nao-autenticado", "Não autenticado", "Token ausente, inválido ou expirado", request.getRequestURI());
    }

    public static AccessDeniedHandler acessoNegado() {
        return (request, response, ex) -> ProblemaHttp.escrever(response, HttpServletResponse.SC_FORBIDDEN,
                "acesso-negado", "Acesso negado", "Perfil sem permissão para este recurso", request.getRequestURI());
    }
}

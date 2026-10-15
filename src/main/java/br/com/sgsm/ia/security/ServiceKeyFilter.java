package br.com.sgsm.ia.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// /ia/whatsapp/** é chamado máquina-a-máquina pelo ms-whatsapp-bot. Além do JWT (que pode ser
// de qualquer usuário), exige o segredo compartilhado no header X-Service-Key — assim um
// usuário com token comum não consegue chamar o classificador direto. Sem chave configurada
// no servidor, nega tudo (fail closed).
public class ServiceKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Key";
    static final String PREFIXO_PROTEGIDO = "/ia/whatsapp/";

    private final byte[] chaveEsperada;

    public ServiceKeyFilter(String chaveEsperada) {
        this.chaveEsperada = (chaveEsperada == null || chaveEsperada.isBlank())
                ? null
                : chaveEsperada.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIXO_PROTEGIDO);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String recebida = request.getHeader(HEADER);
        if (chaveEsperada == null || recebida == null
                || !MessageDigest.isEqual(chaveEsperada, recebida.getBytes(StandardCharsets.UTF_8))) {
            ProblemaHttp.escrever(response, HttpServletResponse.SC_UNAUTHORIZED, "nao-autenticado",
                    "Não autenticado", "Chave de serviço ausente ou inválida", request.getRequestURI());
            return;
        }
        chain.doFilter(request, response);
    }
}

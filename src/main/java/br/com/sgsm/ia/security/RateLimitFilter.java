package br.com.sgsm.ia.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

// Limita as rotas que chamam o LLM (custo por chamada) com janela fixa de 1 minuto no Redis,
// valendo entre réplicas:
//  - chat/resumo de paciente: por usuário autenticado
//  - WhatsApp: global — os usuários não identificados chegam todos com o mesmo token de sistema
//    do bot e a requisição não traz o número, então não há chave por usuário confiável
// Redis indisponível = deixa passar (o rate limit não pode derrubar o serviço).
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String PREFIXO = "sgsm-ia:rate:";
    private static final Duration TTL_JANELA = Duration.ofSeconds(61);

    private final StringRedisTemplate redis;
    private final int limiteChatPorMinuto;
    private final int limiteWhatsAppPorMinuto;
    private final Clock clock;

    public RateLimitFilter(StringRedisTemplate redis, int limiteChatPorMinuto, int limiteWhatsAppPorMinuto, Clock clock) {
        this.redis = redis;
        this.limiteChatPorMinuto = limiteChatPorMinuto;
        this.limiteWhatsAppPorMinuto = limiteWhatsAppPorMinuto;
        this.clock = clock;
    }

    private enum Categoria { CHAT, WHATSAPP }

    private static Categoria categoria(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && uri.equals("/ia/whatsapp/classificar")) {
            return Categoria.WHATSAPP;
        }
        if (("POST".equals(request.getMethod()) && uri.equals("/ia/chat"))
                || ("GET".equals(request.getMethod()) && uri.matches("/ia/paciente/[^/]+/resumo"))) {
            return Categoria.CHAT;
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return categoria(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Categoria categoria = categoria(request);
        String chaveBase;
        int limite;
        if (categoria == Categoria.WHATSAPP) {
            chaveBase = PREFIXO + "whatsapp";
            limite = limiteWhatsAppPorMinuto;
        } else {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) {
                // Sem usuário a cadeia de autorização já responde 401 — nada a contar aqui
                chain.doFilter(request, response);
                return;
            }
            chaveBase = PREFIXO + "chat:" + auth.getName();
            limite = limiteChatPorMinuto;
        }

        long agora = clock.instant().getEpochSecond();
        String chave = chaveBase + ":" + (agora / 60);
        Long chamadas;
        try {
            chamadas = redis.opsForValue().increment(chave);
            if (chamadas != null && chamadas == 1L) {
                redis.expire(chave, TTL_JANELA);
            }
        } catch (Exception e) {
            log.warn("Rate limit indisponível (Redis): {} — requisição liberada", e.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (chamadas != null && chamadas > limite) {
            response.setHeader("Retry-After", String.valueOf(60 - (agora % 60)));
            ProblemaHttp.escrever(response, 429, "limite-excedido", "Limite de requisições excedido",
                    "Muitas requisições ao assistente. Tente novamente em instantes.", request.getRequestURI());
            return;
        }
        chain.doFilter(request, response);
    }
}

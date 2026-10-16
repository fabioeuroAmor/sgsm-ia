package br.com.sgsm.ia.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    // 10:00:45 UTC -> janela (minuto) 29_473_800 desde a época; faltam 15s para a próxima
    private static final Instant AGORA = Instant.parse("2026-01-15T10:00:45Z");
    private static final long JANELA = AGORA.getEpochSecond() / 60;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valores;
    @Mock
    private FilterChain chain;

    private RateLimitFilter filtro;

    @BeforeEach
    void setUp() {
        filtro = new RateLimitFilter(redis, 2, 3, Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    @AfterEach
    void limpar() {
        SecurityContextHolder.clearContext();
    }

    private static void autenticado(String usuario) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(usuario, null, List.of()));
    }

    private static MockHttpServletRequest req(String metodo, String uri) {
        var r = new MockHttpServletRequest(metodo, uri);
        r.setRequestURI(uri);
        return r;
    }

    @Test
    void rotasQueNaoChamamOLlmNaoSaoLimitadas() throws Exception {
        assertThat(filtro.shouldNotFilter(req("GET", "/crm/leads"))).isTrue();
        assertThat(filtro.shouldNotFilter(req("GET", "/ia/kpis"))).isTrue();
        assertThat(filtro.shouldNotFilter(req("GET", "/ia/chat"))).isTrue();
        assertThat(filtro.shouldNotFilter(req("POST", "/ia/chat"))).isFalse();
        assertThat(filtro.shouldNotFilter(req("GET", "/ia/paciente/abc/resumo"))).isFalse();
        assertThat(filtro.shouldNotFilter(req("POST", "/ia/whatsapp/classificar"))).isFalse();
    }

    @Test
    void chatDentroDoLimiteDevePassarEContarPorUsuarioEJanela() throws Exception {
        autenticado("usuario-1");
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.increment("sgsm-ia:rate:chat:usuario-1:" + JANELA)).thenReturn(1L);
        var request = req("POST", "/ia/chat");
        var response = new MockHttpServletResponse();

        filtro.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(redis).expire(eq("sgsm-ia:rate:chat:usuario-1:" + JANELA), any(Duration.class));
    }

    @Test
    void chatAcimaDoLimiteDeveResponder429ComRetryAfter() throws Exception {
        autenticado("usuario-1");
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.increment(anyString())).thenReturn(3L);
        var response = new MockHttpServletResponse();

        filtro.doFilter(req("POST", "/ia/chat"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("15");
        assertThat(response.getContentAsString()).contains("limite-excedido");
        verifyNoInteractions(chain);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void whatsAppUsaContadorGlobalIndependenteDoUsuario() throws Exception {
        autenticado("token-de-sistema");
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.increment("sgsm-ia:rate:whatsapp:" + JANELA)).thenReturn(4L);
        var response = new MockHttpServletResponse();

        filtro.doFilter(req("POST", "/ia/whatsapp/classificar"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void chatSemUsuarioAutenticadoNaoContaEDeixaAAutorizacaoResponder() throws Exception {
        var request = req("POST", "/ia/chat");
        var response = new MockHttpServletResponse();

        filtro.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(redis);
    }

    @Test
    void redisIndisponivelDeveLiberarARequisicao() throws Exception {
        autenticado("usuario-1");
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis fora"));
        var request = req("POST", "/ia/chat");
        var response = new MockHttpServletResponse();

        filtro.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}

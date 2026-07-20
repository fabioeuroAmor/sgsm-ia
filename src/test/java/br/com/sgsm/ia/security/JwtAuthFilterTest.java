package br.com.sgsm.ia.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;
    @Mock
    private Claims claims;

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtService, redis);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void invoke() throws Exception {
        Method m = JwtAuthFilter.class.getDeclaredMethod(
                "doFilterInternal", HttpServletRequest.class, HttpServletResponse.class, FilterChain.class);
        m.setAccessible(true);
        m.invoke(filter, request, response, chain);
    }

    @Test
    void devePassarAdianteQuandoNaoHaHeaderDeAutorizacao() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        invoke();

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void devePassarAdianteQuandoHeaderNaoComecaComBearer() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");

        invoke();

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void devePassarAdianteQuandoTokenInvalido() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer token-invalido");
        when(jwtService.tokenValido("token-invalido")).thenReturn(false);

        invoke();

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void devePassarAdianteQuandoTokenEstaNaBlacklist() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer token-valido");
        when(jwtService.tokenValido("token-valido")).thenReturn(true);
        when(jwtService.extrairClaims("token-valido")).thenReturn(claims);
        when(claims.getId()).thenReturn("jti-123");
        when(redis.hasKey("blacklist:jti-123")).thenReturn(true);

        invoke();

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void deveAutenticarQuandoTokenValidoENaoBlacklisted() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer token-valido");
        when(jwtService.tokenValido("token-valido")).thenReturn(true);
        when(jwtService.extrairClaims("token-valido")).thenReturn(claims);
        when(claims.getId()).thenReturn("jti-123");
        when(redis.hasKey("blacklist:jti-123")).thenReturn(false);
        when(claims.get("roles", List.class)).thenReturn(List.of("MEDICO", "FUNCIONARIO"));
        when(claims.getSubject()).thenReturn("usuario@sgsm.com");
        when(claims.get("referenciaId", String.class)).thenReturn("ref-1");
        when(claims.get("perfil", String.class)).thenReturn("MEDICO");
        when(claims.get("email", String.class)).thenReturn("usuario@sgsm.com");

        invoke();

        verify(chain).doFilter(request, response);
        verify(request).setAttribute("referenciaId", "ref-1");
        verify(request).setAttribute("perfil", "MEDICO");
        verify(request).setAttribute("email", "usuario@sgsm.com");

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("usuario@sgsm.com");
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_MEDICO", "ROLE_FUNCIONARIO");
    }

    @Test
    void deveAutenticarSemAuthoritiesQuandoRolesNulas() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer token-valido");
        when(jwtService.tokenValido("token-valido")).thenReturn(true);
        when(jwtService.extrairClaims("token-valido")).thenReturn(claims);
        when(claims.getId()).thenReturn(null);
        when(claims.get("roles", List.class)).thenReturn(null);
        when(claims.getSubject()).thenReturn("usuario@sgsm.com");

        invoke();

        verify(chain).doFilter(request, response);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).isEmpty();
        verify(redis, never()).hasKey(any());
    }
}

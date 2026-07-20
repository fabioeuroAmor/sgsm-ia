package br.com.sgsm.ia.config;

import br.com.sgsm.ia.security.JwtAuthFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SecurityConfig é uma classe de configuração declarativa (fluent DSL do Spring Security).
 * Em vez de subir um contexto Spring completo só para validar wiring, o HttpSecurity é
 * mockado com Answers.RETURNS_SELF (simula o builder fluente) e o Customizer de
 * authorizeHttpRequests é capturado e executado contra um registry com deep stubs, validando
 * que a árvore de regras de autorização (requestMatchers/permitAll/anyRequest/authenticated)
 * é montada sem lançar exceções.
 */
@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

    @Mock
    private JwtAuthFilter jwtAuthFilter;

    @Test
    @SuppressWarnings("unchecked")
    void deveConstruirFilterChainRegistrandoFiltroJwtERegrasDeAutorizacao() throws Exception {
        var securityConfig = new SecurityConfig(jwtAuthFilter);

        HttpSecurity http = mock(HttpSecurity.class, Answers.RETURNS_SELF);
        DefaultSecurityFilterChain filterChainEsperado = mock(DefaultSecurityFilterChain.class);
        when(http.build()).thenReturn(filterChainEsperado);

        SecurityFilterChain resultado = securityConfig.securityFilterChain(http);

        assertThat(resultado).isSameAs(filterChainEsperado);
        verify(http).addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        ArgumentCaptor<Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry>> captor =
                ArgumentCaptor.forClass(Customizer.class);
        verify(http).authorizeHttpRequests(captor.capture());

        var registry = mock(
                AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry.class,
                Answers.RETURNS_DEEP_STUBS);

        captor.getValue().customize(registry);
    }
}

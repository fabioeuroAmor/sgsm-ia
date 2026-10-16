package br.com.sgsm.ia.config;

import br.com.sgsm.ia.security.JwtAuthFilter;
import br.com.sgsm.ia.security.RateLimitFilter;
import br.com.sgsm.ia.security.ServiceKeyFilter;
import br.com.sgsm.ia.security.SecurityErrorHandlers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String MEDICO = "MEDICO";
    private static final String FUNCIONARIO = "FUNCIONARIO";
    private static final String ADMIN_ESTABELECIMENTO = "ADMIN_ESTABELECIMENTO";
    private static final String DESENVOLVEDOR = "DESENVOLVEDOR";

    private final JwtAuthFilter jwtAuthFilter;
    private final String whatsAppServiceKey;
    private final StringRedisTemplate redis;
    private final int limiteChatPorMinuto;
    private final int limiteWhatsAppPorMinuto;
    private final int limiteMetaPublicoPorMinuto;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          @Value("${ia.whatsapp.service-key:}") String whatsAppServiceKey,
                          StringRedisTemplate redis,
                          @Value("${ia.rate-limit.chat-por-minuto:20}") int limiteChatPorMinuto,
                          @Value("${ia.rate-limit.whatsapp-por-minuto:120}") int limiteWhatsAppPorMinuto,
                          @Value("${ia.rate-limit.meta-publico-por-minuto:60}") int limiteMetaPublicoPorMinuto) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.whatsAppServiceKey = whatsAppServiceKey;
        this.redis = redis;
        this.limiteChatPorMinuto = limiteChatPorMinuto;
        this.limiteWhatsAppPorMinuto = limiteWhatsAppPorMinuto;
        this.limiteMetaPublicoPorMinuto = limiteMetaPublicoPorMinuto;
    }

    // Escopo fino (estabelecimento, posse do paciente) fica nos services via
    // EscopoAcessoService; aqui só a primeira barreira: qual papel chama qual rota.
    // PACIENTE não usa /crm nem /ia (o front não exibe essas telas para ele).
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/health"
                        ).permitAll()
                        // Bot do WhatsApp: token do usuário da sessão (normalmente PACIENTE) ou o
                        // token de sistema; a chave de serviço é exigida pelo ServiceKeyFilter
                        .requestMatchers("/ia/whatsapp/**").authenticated()
                        // Tráfego pago (Meta Ads): quem chama estas é a Meta ou o navegador voltando
                        // dela — validação própria (state de uso único / signed_request) + rate limit
                        .requestMatchers(HttpMethod.GET, "/marketing/meta/callback", "/marketing/meta/exclusao-dados/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/marketing/meta/desautorizacao", "/marketing/meta/exclusao-dados").permitAll()
                        // Demais rotas de tráfego pago: só ADMIN_ESTABELECIMENTO e DESENVOLVEDOR (DM2)
                        .requestMatchers("/marketing/**").hasAnyRole(ADMIN_ESTABELECIMENTO, DESENVOLVEDOR)
                        .requestMatchers("/ia/etl/**").hasRole(DESENVOLVEDOR)
                        .requestMatchers("/ia/kpis").hasAnyRole(ADMIN_ESTABELECIMENTO, DESENVOLVEDOR)
                        .requestMatchers("/crm/churn").hasAnyRole(FUNCIONARIO, ADMIN_ESTABELECIMENTO, DESENVOLVEDOR)
                        .requestMatchers("/crm/leads/**").hasAnyRole(FUNCIONARIO, ADMIN_ESTABELECIMENTO, DESENVOLVEDOR)
                        .requestMatchers(HttpMethod.POST, "/crm/paciente/*/notas").hasRole(MEDICO)
                        .requestMatchers("/crm/paciente/*/notas").hasAnyRole(MEDICO, DESENVOLVEDOR)
                        .requestMatchers("/crm/**", "/ia/**")
                        .hasAnyRole(MEDICO, FUNCIONARIO, ADMIN_ESTABELECIMENTO, DESENVOLVEDOR)
                        .anyRequest().denyAll()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(SecurityErrorHandlers.naoAutenticado())
                        .accessDeniedHandler(SecurityErrorHandlers.acessoNegado()))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ServiceKeyFilter(whatsAppServiceKey), JwtAuthFilter.class)
                // Depois do JWT: o limite do chat é por usuário autenticado
                .addFilterAfter(new RateLimitFilter(redis, limiteChatPorMinuto, limiteWhatsAppPorMinuto,
                        limiteMetaPublicoPorMinuto, Clock.systemUTC()), JwtAuthFilter.class)
                .build();
    }

    // JwtAuthFilter é @Component, então o Spring Boot também o registraria como filtro de
    // servlet global, fora da cadeia do Spring Security. Se essa cópia rodar antes da cadeia,
    // o SecurityContextHolderFilter descarta a autenticação e a cópia de dentro da cadeia é
    // pulada (OncePerRequestFilter) — resultado: 401 com token válido. Ele só roda na cadeia.
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        var registro = new FilterRegistrationBean<>(filter);
        registro.setEnabled(false);
        return registro;
    }
}

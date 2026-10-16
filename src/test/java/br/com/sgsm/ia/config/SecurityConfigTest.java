package br.com.sgsm.ia.config;

import br.com.sgsm.ia.controller.CrmController;
import br.com.sgsm.ia.controller.IaController;
import br.com.sgsm.ia.controller.WhatsAppController;
import br.com.sgsm.ia.marketing.meta.MetaAcessoService;
import br.com.sgsm.ia.marketing.meta.MetaAdsController;
import br.com.sgsm.ia.marketing.meta.MetaContaService;
import br.com.sgsm.ia.marketing.meta.MetaMetricasService;
import br.com.sgsm.ia.marketing.meta.MetaOAuthService;
import br.com.sgsm.ia.marketing.meta.MetaSyncService;
import br.com.sgsm.ia.marketing.meta.MetaWebhookService;
import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.security.JwtService;
import br.com.sgsm.ia.service.AssistenteMedicoService;
import br.com.sgsm.ia.service.CrmService;
import br.com.sgsm.ia.service.EtlSyncService;
import br.com.sgsm.ia.service.KpiService;
import br.com.sgsm.ia.service.RagAutorizacaoService;
import br.com.sgsm.ia.service.WhatsAppService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Matriz de autorização do sgsm-ia passando pela cadeia REAL do Spring Security
 * (JwtAuthFilter + ServiceKeyFilter + regras do SecurityConfig), com JWTs assinados com o
 * mesmo segredo que o serviço valida. Os services são mocks: aqui só importa se a requisição
 * chega ao controller (2xx) ou é barrada na entrada (401/403). O escopo fino por
 * estabelecimento/paciente é coberto nos testes de service.
 *
 * É o teste que teria detectado o Achado de Segurança do sgsm-ia (qualquer role, inclusive
 * PACIENTE autocadastrado, lendo /crm e /ia).
 */
@WebMvcTest(
        controllers = {CrmController.class, IaController.class, WhatsAppController.class, MetaAdsController.class},
        properties = "ia.whatsapp.service-key=" + SecurityConfigTest.CHAVE_SERVICO)
@Import({SecurityConfig.class, SecurityConfigTest.JwtTestConfig.class})
class SecurityConfigTest {

    static final String CHAVE_SERVICO = "chave-servico-teste";
    private static final String SEGREDO = "segredo-de-teste-com-pelo-menos-256-bits-para-hmac-sha";

    private static final String PACIENTE = "PACIENTE";
    private static final String MEDICO = "MEDICO";
    private static final String FUNCIONARIO = "FUNCIONARIO";
    private static final String ADMIN = "ADMIN_ESTABELECIMENTO";
    private static final String DEV = "DESENVOLVEDOR";
    private static final List<String> TODOS_OS_PAPEIS = List.of(PACIENTE, MEDICO, FUNCIONARIO, ADMIN, DEV);

    @TestConfiguration
    static class JwtTestConfig {
        @Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }

        @Bean
        JwtService jwtService() {
            return new JwtService(new JwtProperties(SEGREDO));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private StringRedisTemplate redis;
    @MockitoBean private CrmService crmService;
    @MockitoBean private AssistenteMedicoService assistenteMedicoService;
    @MockitoBean private RagAutorizacaoService ragAutorizacaoService;
    @MockitoBean private KpiService kpiService;
    @MockitoBean private EtlSyncService etlSyncService;
    @MockitoBean private EscopoAcessoService escopoAcessoService;
    @MockitoBean private WhatsAppService whatsAppService;
    @MockitoBean private MetaAcessoService metaAcessoService;
    @MockitoBean private MetaOAuthService metaOAuthService;
    @MockitoBean private MetaContaService metaContaService;
    @MockitoBean private MetaSyncService metaSyncService;
    @MockitoBean private MetaMetricasService metaMetricasService;
    @MockitoBean private MetaWebhookService metaWebhookService;

    private static String token(String role) {
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuer("sgsm-auth")
                .audience().add("sgsm-ia").and()
                .claim("perfil", role)
                .claim("referenciaId", UUID.randomUUID().toString())
                .claim("roles", List.of(role))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SEGREDO.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static MockHttpServletRequestBuilder req(HttpMethod metodo, String path) {
        return request(metodo, path).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private record Rota(HttpMethod metodo, String path, Set<String> permitidos) {
        @Override
        public String toString() {
            return metodo + " " + path;
        }
    }

    private static final String PACIENTE_ID = UUID.randomUUID().toString();

    private static final List<Rota> ROTAS = List.of(
            new Rota(HttpMethod.GET, "/crm/leads", Set.of(FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.POST, "/crm/leads", Set.of(FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.PATCH, "/crm/leads/" + UUID.randomUUID() + "/status", Set.of(FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/crm/paciente/" + PACIENTE_ID + "/tags", Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.POST, "/crm/paciente/" + PACIENTE_ID + "/tags", Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.DELETE, "/crm/tags/" + UUID.randomUUID(), Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/crm/paciente/" + PACIENTE_ID + "/contatos", Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.POST, "/crm/paciente/" + PACIENTE_ID + "/contatos", Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/crm/paciente/" + PACIENTE_ID + "/notas", Set.of(MEDICO, DEV)),
            new Rota(HttpMethod.POST, "/crm/paciente/" + PACIENTE_ID + "/notas", Set.of(MEDICO)),
            new Rota(HttpMethod.GET, "/crm/paciente/" + PACIENTE_ID + "/360", Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/crm/churn", Set.of(FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.POST, "/ia/chat", Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/ia/busca?q=cardiologia", Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/ia/paciente/" + PACIENTE_ID + "/resumo", Set.of(MEDICO, FUNCIONARIO, ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/ia/kpis", Set.of(ADMIN, DEV)),
            new Rota(HttpMethod.POST, "/ia/etl/sync", Set.of(DEV)),
            // Tráfego pago (Meta Ads): só ADMIN_ESTABELECIMENTO e DESENVOLVEDOR (DM2)
            new Rota(HttpMethod.POST, "/marketing/meta/estabelecimentos/" + PACIENTE_ID + "/conexao/iniciar", Set.of(ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/marketing/meta/estabelecimentos/" + PACIENTE_ID + "/conexao", Set.of(ADMIN, DEV)),
            new Rota(HttpMethod.DELETE, "/marketing/meta/estabelecimentos/" + PACIENTE_ID + "/conexao", Set.of(ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/marketing/meta/estabelecimentos/" + PACIENTE_ID + "/contas-disponiveis", Set.of(ADMIN, DEV)),
            new Rota(HttpMethod.PUT, "/marketing/meta/estabelecimentos/" + PACIENTE_ID + "/contas", Set.of(ADMIN, DEV)),
            new Rota(HttpMethod.POST, "/marketing/meta/estabelecimentos/" + PACIENTE_ID + "/sincronizar", Set.of(ADMIN, DEV)),
            new Rota(HttpMethod.GET, "/marketing/meta/estabelecimentos/" + PACIENTE_ID + "/metricas", Set.of(ADMIN, DEV))
    );

    static Stream<Arguments> matrizRotaPapel() {
        return ROTAS.stream().flatMap(rota -> TODOS_OS_PAPEIS.stream()
                .map(papel -> Arguments.of(rota, papel, rota.permitidos().contains(papel))));
    }

    static Stream<Rota> rotas() {
        return ROTAS.stream();
    }

    @ParameterizedTest(name = "{0} como {1} -> permitido={2}")
    @MethodSource("matrizRotaPapel")
    void deveAplicarMatrizDeAutorizacaoPorPapel(Rota rota, String papel, boolean permitido) throws Exception {
        int status = mockMvc.perform(req(rota.metodo(), rota.path())
                        .header("Authorization", "Bearer " + token(papel)))
                .andReturn().getResponse().getStatus();

        if (permitido) {
            assertThat(status).as("%s como %s deveria passar", rota, papel).isNotIn(401, 403);
        } else {
            assertThat(status).as("%s como %s deveria ser barrado", rota, papel).isEqualTo(403);
        }
    }

    @ParameterizedTest(name = "{0} sem token -> 401")
    @MethodSource("rotas")
    void deveExigirAutenticacaoEmTodasAsRotas(Rota rota) throws Exception {
        mockMvc.perform(req(rota.metodo(), rota.path()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // Quem chama estas é a Meta (ou o navegador voltando dela): sem JWT, mas também sem 401/403 —
    // a validação é o state de uso único ou a assinatura do signed_request
    @Test
    void rotasChamadasPelaMetaNaoExigemJwt() throws Exception {
        org.mockito.Mockito.when(metaOAuthService.callback(any(), any(), any())).thenReturn("http://localhost:3001/trafego-pago");

        mockMvc.perform(request(HttpMethod.GET, "/marketing/meta/callback?code=c&state=s"))
                .andExpect(status().isFound());
        mockMvc.perform(request(HttpMethod.POST, "/marketing/meta/desautorizacao")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("signed_request=x.y"))
                .andExpect(status().isOk());
        mockMvc.perform(request(HttpMethod.POST, "/marketing/meta/exclusao-dados")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("signed_request=x.y"))
                .andExpect(status().isOk());
        mockMvc.perform(request(HttpMethod.GET, "/marketing/meta/exclusao-dados/abc"))
                .andExpect(status().isOk());
    }

    @Test
    void deveRejeitarTokenAssinadoComOutroSegredo() throws Exception {
        String forjado = Jwts.builder()
                .subject("atacante")
                .claim("roles", List.of(DEV))
                .signWith(Keys.hmacShaKeyFor("outro-segredo-qualquer-com-mais-de-256-bits-xxxxxx".getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(req(HttpMethod.GET, "/ia/kpis").header("Authorization", "Bearer " + forjado))
                .andExpect(status().isUnauthorized());
    }

    // Assinado com o segredo certo, mas sem iss/aud (emitido antes da Fase 8 ou só para o sgsm)
    @Test
    void deveRejeitarTokenSemAudienciaDoSgsmIa() throws Exception {
        String soParaSgsm = Jwts.builder()
                .subject("usuario")
                .issuer("sgsm-auth")
                .audience().add("sgsm").and()
                .claim("roles", List.of(DEV))
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SEGREDO.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(req(HttpMethod.GET, "/ia/kpis").header("Authorization", "Bearer " + soParaSgsm))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deveRejeitarTokenNaBlacklist() throws Exception {
        when(redis.hasKey(anyString())).thenReturn(true);

        mockMvc.perform(req(HttpMethod.GET, "/ia/kpis").header("Authorization", "Bearer " + token(DEV)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @SuppressWarnings("unchecked")
    void chatAcimaDoLimiteDeveResponder429NaCadeiaReal() throws Exception {
        var valores = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.increment(anyString())).thenReturn(1_000L);

        mockMvc.perform(req(HttpMethod.POST, "/ia/chat").header("Authorization", "Bearer " + token(MEDICO)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void deveNegarRotaNaoMapeadaMesmoParaDesenvolvedor() throws Exception {
        mockMvc.perform(req(HttpMethod.GET, "/rota/inexistente").header("Authorization", "Bearer " + token(DEV)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ── WhatsApp: JWT + chave de serviço ──────────────────────────────────────

    @Test
    void whatsAppSemChaveDeServicoDeveRetornar401MesmoComToken() throws Exception {
        mockMvc.perform(req(HttpMethod.POST, "/ia/whatsapp/classificar")
                        .header("Authorization", "Bearer " + token(PACIENTE)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void whatsAppComChaveErradaDeveRetornar401() throws Exception {
        mockMvc.perform(req(HttpMethod.POST, "/ia/whatsapp/classificar")
                        .header("Authorization", "Bearer " + token(PACIENTE))
                        .header("X-Service-Key", "chave-errada"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void whatsAppComChaveMasSemTokenDeveRetornar401() throws Exception {
        mockMvc.perform(req(HttpMethod.POST, "/ia/whatsapp/classificar")
                        .header("X-Service-Key", CHAVE_SERVICO))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void whatsAppComChaveETokenDePacienteDevePassar() throws Exception {
        mockMvc.perform(req(HttpMethod.POST, "/ia/whatsapp/classificar")
                        .header("Authorization", "Bearer " + token(PACIENTE))
                        .header("X-Service-Key", CHAVE_SERVICO))
                .andExpect(status().isOk());
    }

    @Test
    void whatsAppComChaveETokenDeSistemaDevePassar() throws Exception {
        mockMvc.perform(req(HttpMethod.POST, "/ia/whatsapp/classificar")
                        .header("Authorization", "Bearer " + token(DEV))
                        .header("X-Service-Key", CHAVE_SERVICO))
                .andExpect(status().isOk());
    }
}

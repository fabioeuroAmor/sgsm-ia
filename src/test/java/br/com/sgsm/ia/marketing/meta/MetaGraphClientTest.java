package br.com.sgsm.ia.marketing.meta;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Servidor HTTP local no lugar da Graph API: prova as URLs, parâmetros e o tratamento das
 * respostas reais do cliente (paginação, erros, cabeçalhos de uso), sem chamar a Meta.
 */
class MetaGraphClientTest {

    private HttpServer servidor;
    private String base;
    private final List<String> consultasRecebidas = new CopyOnWriteArrayList<>();
    private MetaGraphClient client;

    @BeforeEach
    void setUp() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        servidor.createContext("/v23.0/me/adaccounts", troca -> {
            String query = troca.getRequestURI().getRawQuery();
            consultasRecebidas.add(URLDecoder.decode(query, StandardCharsets.UTF_8));
            if (query.contains("after=PAG2")) {
                responder(troca, 200, """
                        {"data":[{"id":"act_2","name":"Conta 2"}]}
                        """, Map.of());
            } else {
                responder(troca, 200, """
                        {"data":[{"id":"act_1","name":"Conta 1"}],
                         "paging":{"next":"%s/v23.0/me/adaccounts?after=PAG2&access_token=tk&appsecret_proof=x"}}
                        """.formatted(base), Map.of("X-App-Usage", "{\"call_count\":12,\"total_time\":35,\"total_cputime\":7}"));
            }
        });
        servidor.createContext("/v23.0/me", troca -> responder(troca, 200, """
                {"id":"999","name":"Ana Admin"}
                """, Map.of("X-Business-Use-Case-Usage",
                "{\"123\":[{\"type\":\"ads_insights\",\"call_count\":91,\"estimated_time_to_regain_access\":500}]}")));
        servidor.createContext("/v23.0/expirado", troca -> responder(troca, 400, """
                {"error":{"message":"Error validating access token","type":"OAuthException","code":190}}
                """, Map.of()));
        servidor.createContext("/v23.0/limite", troca -> responder(troca, 400, """
                {"error":{"message":"User request limit reached","code":17}}
                """, Map.of()));
        servidor.createContext("/v23.0/vazio", troca -> responder(troca, 200, "", Map.of()));
        servidor.createContext("/v23.0/me/permissions", troca -> responder(troca, 200, """
                {"success":true}
                """, Map.of()));
        servidor.createContext("/v23.0/pagina-externa", troca -> responder(troca, 200, """
                {"data":[],"paging":{"next":"https://site-malicioso.example/roubar?access_token=tk"}}
                """, Map.of()));
        servidor.start();
        client = new MetaGraphClient(MetaFixtures.props(base), MetaFixtures.MAPPER);
    }

    @AfterEach
    void tearDown() {
        servidor.stop(0);
    }

    private static void responder(HttpExchange troca, int status, String corpo, Map<String, String> headers) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        headers.forEach((k, v) -> troca.getResponseHeaders().add(k, v));
        troca.getResponseHeaders().add("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            troca.getResponseBody().write(bytes);
        }
        troca.close();
    }

    @Test
    void deveSeguirAPaginacaoEJuntarTodasAsPaginas() {
        var contas = client.getTodos("/me/adaccounts", Map.of("fields", "id,name"), "tk");

        assertThat(contas).extracting(c -> c.path("id").asText()).containsExactly("act_1", "act_2");
        assertThat(consultasRecebidas).hasSize(2);
    }

    @Test
    void deveEnviarTokenEAppsecretProofEmTodaChamada() {
        client.getTodos("/me/adaccounts", Map.of("fields", "id,name"), "tk");

        assertThat(consultasRecebidas.get(0))
                .contains("fields=id,name")
                .contains("access_token=tk")
                .contains("appsecret_proof=" + client.appsecretProof("tk"));
    }

    @Test
    void appsecretProofDeveSerHmacSha256EmHexadecimal() {
        // HMAC-SHA256("tk", "segredo-do-app") — mesmo cálculo que a Meta faz para validar
        assertThat(client.appsecretProof("tk")).hasSize(64).matches("[0-9a-f]+");
        assertThat(client.appsecretProof("tk")).isEqualTo(client.appsecretProof("tk"));
        assertThat(client.appsecretProof("tk")).isNotEqualTo(client.appsecretProof("outro"));
    }

    @Test
    void deveRegistrarOMaiorPercentualDeUsoIgnorandoCamposQueNaoSaoPercentuais() {
        client.getTodos("/me/adaccounts", Map.of(), "tk");
        // última resposta (página 2) não trouxe cabeçalho: uso volta a 0
        assertThat(client.getUltimoUsoPercentual()).isZero();

        client.get("/me", Map.of(), "tk");
        // 91, não 500 (estimated_time_to_regain_access é tempo, não percentual)
        assertThat(client.getUltimoUsoPercentual()).isEqualTo(91);
    }

    @Test
    void erroDeTokenDeveVirarMetaApiExceptionDeTokenInvalido() {
        assertThatThrownBy(() -> client.get("/expirado", Map.of(), "tk"))
                .isInstanceOf(MetaApiException.class)
                .satisfies(e -> {
                    var erro = (MetaApiException) e;
                    assertThat(erro.tokenInvalido()).isTrue();
                    assertThat(erro.limiteDeUso()).isFalse();
                    assertThat(erro.getStatus()).isEqualTo(400);
                    assertThat(erro.getCodigo()).isEqualTo(190);
                });
    }

    @Test
    void erroDeLimiteDeveSerIdentificado() {
        assertThatThrownBy(() -> client.get("/limite", Map.of(), "tk"))
                .isInstanceOf(MetaApiException.class)
                .satisfies(e -> assertThat(((MetaApiException) e).limiteDeUso()).isTrue());
    }

    @Test
    void respostaVaziaDeveVirarObjetoVazio() {
        assertThat(client.get("/vazio", Map.of(), null).isEmpty()).isTrue();
    }

    @Test
    void deleteDeveFuncionar() {
        assertThat(client.delete("/me/permissions", "tk").path("success").asBoolean()).isTrue();
    }

    // O token iria junto na URL de paginação: não segue para outro host
    @Test
    void naoDeveSeguirPaginacaoParaOutroHost() {
        assertThatThrownBy(() -> client.getTodos("/pagina-externa", Map.of(), "tk"))
                .isInstanceOf(MetaApiException.class)
                .hasMessageContaining("host inesperado");
    }

    @Test
    void semTokenNaoDeveMandarAppsecretProof() {
        var uri = client.montarUri("/oauth/access_token", Map.of("code", "c d"), null);

        assertThat(uri.toString()).startsWith(base + "/v23.0/oauth/access_token?code=c+d").doesNotContain("appsecret_proof");
    }

    @Test
    void baseComBarraNoFimDeveSerNormalizada() {
        var comBarra = new MetaGraphClient(MetaFixtures.props(base + "/"), MetaFixtures.MAPPER);

        assertThat(comBarra.montarUri("/me", Map.of(), null).toString()).isEqualTo(base + "/v23.0/me");
    }
}

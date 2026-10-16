package br.com.sgsm.ia.marketing.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// Cliente mínimo da Graph API / Marketing API da Meta.
//  - toda chamada com token leva appsecret_proof (HMAC-SHA256 do token com o APP_SECRET)
//  - paginação seguindo paging.next, só para URLs do próprio host da Graph API
//  - erro da Meta vira MetaApiException (token inválido, limite de uso etc.)
//  - lê os cabeçalhos de uso (X-App-Usage, X-Ad-Account-Usage, X-Business-Use-Case-Usage)
//    para a sincronização desacelerar antes de bater no limite
@Component
public class MetaGraphClient {

    static final int MAX_PAGINAS = 50;

    private final MetaProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;
    private volatile int ultimoUsoPercentual;

    public MetaGraphClient(MetaProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        var fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(Duration.ofSeconds(10));
        fabrica.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().requestFactory(fabrica).build();
    }

    public JsonNode get(String caminho, Map<String, String> params, String token) {
        return executar(HttpMethod.GET, montarUri(caminho, params, token));
    }

    public JsonNode delete(String caminho, String token) {
        return executar(HttpMethod.DELETE, montarUri(caminho, Map.of(), token));
    }

    // Junta todas as páginas de "data". Limite de páginas evita laço infinito se a Meta
    // devolver paging.next repetido.
    public List<JsonNode> getTodos(String caminho, Map<String, String> params, String token) {
        List<JsonNode> itens = new ArrayList<>();
        URI proxima = montarUri(caminho, params, token);
        for (int pagina = 0; proxima != null && pagina < MAX_PAGINAS; pagina++) {
            JsonNode resposta = executar(HttpMethod.GET, proxima);
            resposta.path("data").forEach(itens::add);
            String next = resposta.path("paging").path("next").asText(null);
            proxima = next != null ? uriDePaginacao(next) : null;
        }
        return itens;
    }

    public int getUltimoUsoPercentual() {
        return ultimoUsoPercentual;
    }

    URI montarUri(String caminho, Map<String, String> params, String token) {
        Map<String, String> query = new LinkedHashMap<>(params);
        if (token != null) {
            query.put("access_token", token);
            query.put("appsecret_proof", appsecretProof(token));
        }
        String qs = query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(base() + "/" + props.graphVersion() + caminho + (qs.isEmpty() ? "" : "?" + qs));
    }

    // paging.next já vem com access_token e appsecret_proof; só aceita o host da própria Graph API
    // (o token iria junto para qualquer outro endereço)
    private URI uriDePaginacao(String next) {
        if (!next.startsWith(base() + "/")) {
            throw new MetaApiException(0, 0, "Paginação da Meta apontou para host inesperado");
        }
        return URI.create(next);
    }

    String appsecretProof(String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao calcular appsecret_proof", e);
        }
    }

    private String base() {
        String base = props.graphBaseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private JsonNode executar(HttpMethod metodo, URI uri) {
        return http.method(metodo).uri(uri).exchange((requisicao, resposta) -> {
            registrarUso(resposta.getHeaders());
            String corpo = new String(resposta.getBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = lerJson(corpo);
            int status = resposta.getStatusCode().value();
            if (status >= 400 || json.has("error")) {
                JsonNode erro = json.path("error");
                throw new MetaApiException(status, erro.path("code").asInt(0),
                        erro.path("message").asText("Erro HTTP " + status + " da Meta"));
            }
            return json;
        });
    }

    private JsonNode lerJson(String corpo) throws IOException {
        if (corpo == null || corpo.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(corpo);
    }

    // Cada cabeçalho traz percentuais (call_count, total_time, total_cputime, acc_id_util_pct...);
    // guarda o maior deles da última resposta
    private void registrarUso(HttpHeaders headers) {
        int maior = 0;
        for (String nome : List.of("X-App-Usage", "X-Ad-Account-Usage", "X-Business-Use-Case-Usage")) {
            String valor = headers.getFirst(nome);
            if (valor != null) {
                maior = Math.max(maior, maiorPercentual(valor));
            }
        }
        ultimoUsoPercentual = maior;
    }

    private int maiorPercentual(String json) {
        try {
            List<Integer> valores = new ArrayList<>();
            coletarNumeros(mapper.readTree(json), valores);
            return valores.stream().max(Integer::compare).orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    // Só os campos de percentual — o cabeçalho também traz, por exemplo,
    // estimated_time_to_regain_access (em minutos), que não é uso
    private static final java.util.Set<String> CAMPOS_PERCENTUAIS =
            java.util.Set.of("call_count", "total_time", "total_cputime", "acc_id_util_pct");

    private static void coletarNumeros(JsonNode no, List<Integer> valores) {
        if (no.isObject()) {
            no.properties().forEach(campo -> {
                if (CAMPOS_PERCENTUAIS.contains(campo.getKey()) && campo.getValue().isNumber()) {
                    valores.add(campo.getValue().asInt());
                } else {
                    coletarNumeros(campo.getValue(), valores);
                }
            });
        } else if (no.isArray()) {
            no.forEach(filho -> coletarNumeros(filho, valores));
        }
    }
}

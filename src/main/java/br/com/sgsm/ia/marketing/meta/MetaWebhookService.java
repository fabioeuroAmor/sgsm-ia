package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.AcessoNegadoException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Chamadas que a própria Meta faz ao SGSM (obrigatórias para o App Review):
//  - desautorização: o usuário removeu o app nas configurações da Meta
//  - exclusão de dados: o usuário pediu para apagar os dados que o app guardou
// Chegam como signed_request ("assinatura.payload", base64url) assinado com HMAC-SHA256 do
// APP_SECRET — sem assinatura válida, nada é feito.
@Service
public class MetaWebhookService {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookService.class);

    static final String PREFIXO_EXCLUSAO = "sgsm-ia:meta:exclusao:";
    static final Duration TTL_EXCLUSAO = Duration.ofDays(90);

    public record RespostaExclusao(String url, String confirmation_code) {}

    private final MetaProperties props;
    private final MetaRepository repo;
    private final MetaRagIndexador ragIndexador;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public MetaWebhookService(MetaProperties props, MetaRepository repo, MetaRagIndexador ragIndexador,
                              StringRedisTemplate redis, ObjectMapper mapper) {
        this.props = props;
        this.repo = repo;
        this.ragIndexador = ragIndexador;
        this.redis = redis;
        this.mapper = mapper;
    }

    public void desautorizar(String signedRequest) {
        String metaUserId = usuarioDoPayload(verificar(signedRequest));
        List<UUID> estabelecimentos = repo.estabelecimentosDoUsuarioMeta(metaUserId);
        for (UUID estabelecimentoId : estabelecimentos) {
            repo.marcarStatus(estabelecimentoId, MetaRepository.REVOGADA, "App removido pelo usuário na Meta");
        }
        log.info("Desautorização da Meta: {} conexão(ões) revogada(s)", estabelecimentos.size());
    }

    public RespostaExclusao excluirDados(String signedRequest) {
        String metaUserId = usuarioDoPayload(verificar(signedRequest));
        List<UUID> estabelecimentos = repo.estabelecimentosDoUsuarioMeta(metaUserId);
        // Banco e resumo do assistente (Milvus): o código só é devolvido depois dos dois
        for (UUID estabelecimentoId : estabelecimentos) {
            repo.excluirTudo(estabelecimentoId);
            ragIndexador.remover(estabelecimentoId);
        }
        String codigo = UUID.randomUUID().toString();
        redis.opsForValue().set(PREFIXO_EXCLUSAO + codigo, "CONCLUIDA", TTL_EXCLUSAO);
        log.info("Exclusão de dados pedida pela Meta: {} clínica(s), código {}", estabelecimentos.size(), codigo);
        String url = UriComponentsBuilder.fromUriString(props.redirectUri())
                .replacePath("/marketing/meta/exclusao-dados/" + codigo)
                .replaceQuery(null)
                .build().toUriString();
        return new RespostaExclusao(url, codigo);
    }

    // Página de status que a Meta mostra ao usuário depois do pedido de exclusão
    public Map<String, String> statusExclusao(String codigo) {
        String status = redis.opsForValue().get(PREFIXO_EXCLUSAO + codigo);
        return Map.of("codigo", codigo, "status", status != null ? status : "NAO_ENCONTRADA");
    }

    JsonNode verificar(String signedRequest) {
        if (signedRequest == null || !signedRequest.contains(".")) {
            throw new AcessoNegadoException("signed_request ausente ou malformado");
        }
        String[] partes = signedRequest.split("\\.", 2);
        try {
            byte[] assinatura = Base64.getUrlDecoder().decode(partes[0]);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.appSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] esperado = mac.doFinal(partes[1].getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(esperado, assinatura)) {
                throw new AcessoNegadoException("Assinatura do signed_request inválida");
            }
            JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(partes[1]));
            if (!"HMAC-SHA256".equalsIgnoreCase(payload.path("algorithm").asText())) {
                throw new AcessoNegadoException("Algoritmo do signed_request não suportado");
            }
            return payload;
        } catch (IllegalArgumentException | IOException | GeneralSecurityException e) {
            throw new AcessoNegadoException("signed_request inválido");
        }
    }

    private static String usuarioDoPayload(JsonNode payload) {
        String userId = payload.path("user_id").asText(null);
        if (userId == null || userId.isBlank()) {
            throw new AcessoNegadoException("signed_request sem user_id");
        }
        return userId;
    }
}

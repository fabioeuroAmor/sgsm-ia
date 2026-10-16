package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.marketing.meta.MetaRepository.Conexao;
import br.com.sgsm.ia.security.ContextoSeguranca;
import br.com.sgsm.ia.service.AuditoriaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// Fluxo OAuth com o Facebook Login for Business (Fase 3 do plano):
//  1. iniciar: gera um state aleatório de uso único no Redis (TTL 10 min), ligado à clínica e a
//     quem iniciou, e devolve a URL de login da Meta
//  2. callback: consome o state, troca o code por token no servidor (com o APP_SECRET), descobre
//     o tipo e a validade do token, cifra e grava; devolve para onde o navegador vai
//  3. desconectar: revoga na Meta, apaga o token e mantém as métricas (DM6)
@Service
public class MetaOAuthService {

    private static final Logger log = LoggerFactory.getLogger(MetaOAuthService.class);

    static final String PREFIXO_STATE = "sgsm-ia:meta:oauth:state:";
    static final Duration TTL_STATE = Duration.ofMinutes(10);
    static final String ESCOPO_NECESSARIO = "ads_read";
    static final String ENTIDADE_AUDITORIA = "META_CONEXAO";

    public record EstadoOAuth(String estabelecimentoId, String usuarioId, String referenciaId, String perfil, String email) {}

    public record StatusConexao(boolean conectado, String status, String metaUsuario, String tipoToken,
                                OffsetDateTime expiraEm, OffsetDateTime ultimoSyncEm, String ultimoErro) {
        static StatusConexao desconectado() {
            return new StatusConexao(false, "NAO_CONECTADA", null, null, null, null, null);
        }
    }

    private final MetaProperties props;
    private final MetaGraphClient graph;
    private final MetaRepository repo;
    private final MetaTokenCryptoService crypto;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ContextoSeguranca contexto;
    private final AuditoriaService auditoria;
    private final SecureRandom aleatorio = new SecureRandom();

    public MetaOAuthService(MetaProperties props, MetaGraphClient graph, MetaRepository repo,
                            MetaTokenCryptoService crypto, StringRedisTemplate redis, ObjectMapper mapper,
                            ContextoSeguranca contexto, AuditoriaService auditoria) {
        this.props = props;
        this.graph = graph;
        this.repo = repo;
        this.crypto = crypto;
        this.redis = redis;
        this.mapper = mapper;
        this.contexto = contexto;
        this.auditoria = auditoria;
    }

    // ── 1. iniciar ────────────────────────────────────────────────────────────

    public String iniciar(UUID estabelecimentoId) {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var estado = new EstadoOAuth(estabelecimentoId.toString(), contexto.getUsuarioId(),
                contexto.getReferenciaIdStr(), contexto.getPerfil(), contexto.getEmail());
        redis.opsForValue().set(PREFIXO_STATE + state, json(estado), TTL_STATE);

        var url = UriComponentsBuilder.fromUriString(props.dialogBaseUrl())
                .path("/" + props.graphVersion() + "/dialog/oauth")
                .queryParam("client_id", props.appId())
                .queryParam("redirect_uri", props.redirectUri())
                .queryParam("state", state)
                .queryParam("response_type", "code");
        // Login for Business usa o Configuration ID (define permissões e tipo de token no painel
        // da Meta); sem ele, pede só ads_read pelo fluxo clássico
        if (props.loginConfigId() != null && !props.loginConfigId().isBlank()) {
            url.queryParam("config_id", props.loginConfigId());
        } else {
            url.queryParam("scope", ESCOPO_NECESSARIO);
        }
        return url.encode().build().toUriString();
    }

    // ── 2. callback ───────────────────────────────────────────────────────────

    // Nunca lança: sempre devolve a URL do front com o resultado (o navegador está no meio do fluxo)
    public String callback(String code, String state, String erroMeta) {
        EstadoOAuth estado = consumirState(state);
        if (estado == null) {
            return retornoFront(null, "erro", "state_invalido");
        }
        if (erroMeta != null || code == null || code.isBlank()) {
            return retornoFront(estado.estabelecimentoId(), "cancelado", null);
        }
        try {
            conectar(estado, code);
            return retornoFront(estado.estabelecimentoId(), "conectado", null);
        } catch (PermissaoInsuficienteException e) {
            return retornoFront(estado.estabelecimentoId(), "erro", "permissao_negada");
        } catch (RuntimeException e) {
            log.warn("Falha ao concluir conexão com a Meta para estabelecimento={}: {}",
                    estado.estabelecimentoId(), e.getMessage());
            return retornoFront(estado.estabelecimentoId(), "erro", "falha_meta");
        }
    }

    private void conectar(EstadoOAuth estado, String code) {
        JsonNode troca = graph.get("/oauth/access_token", Map.of(
                "client_id", props.appId(),
                "client_secret", props.appSecret(),
                "redirect_uri", props.redirectUri(),
                "code", code), null);
        String token = troca.path("access_token").asText();

        JsonNode info = depurar(token);
        if (!temEscopo(info)) {
            throw new PermissaoInsuficienteException();
        }

        String tipo;
        OffsetDateTime expiraEm;
        long expiraEpoch = info.path("expires_at").asLong(0);
        if (expiraEpoch == 0) {
            // Token de usuário do sistema (Login for Business): não expira (DM4)
            tipo = "USUARIO_SISTEMA";
            expiraEm = null;
        } else {
            // Token de usuário: troca pelo de longa duração (~60 dias) e acompanha a expiração
            JsonNode longo = graph.get("/oauth/access_token", Map.of(
                    "grant_type", "fb_exchange_token",
                    "client_id", props.appId(),
                    "client_secret", props.appSecret(),
                    "fb_exchange_token", token), null);
            token = longo.path("access_token").asText(token);
            tipo = "USUARIO_LONGA_DURACAO";
            long segundos = longo.path("expires_in").asLong(0);
            expiraEm = segundos > 0
                    ? OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(segundos)
                    : Instant.ofEpochSecond(expiraEpoch).atOffset(ZoneOffset.UTC);
        }

        JsonNode eu = graph.get("/me", Map.of("fields", "id,name"), token);
        UUID estabelecimentoId = UUID.fromString(estado.estabelecimentoId());
        repo.salvarConexao(estabelecimentoId, eu.path("id").asText(null), eu.path("name").asText(null), tipo,
                crypto.cifrar(token), escopos(info), expiraEm, estado.usuarioId());
        auditoria.registrar(ENTIDADE_AUDITORIA, estado.estabelecimentoId(), "CRIACAO",
                estado.referenciaId(), estado.perfil(), estado.email());
    }

    // ── 3. status / desconectar ───────────────────────────────────────────────

    public StatusConexao status(UUID estabelecimentoId) {
        return repo.buscarConexao(estabelecimentoId)
                .map(c -> new StatusConexao(MetaRepository.ATIVA.equals(c.status()), c.status(), c.metaUserNome(),
                        c.tipoToken(), c.expiraEm(), c.ultimoSyncEm(), c.ultimoErro()))
                .orElseGet(StatusConexao::desconectado);
    }

    public void desconectar(UUID estabelecimentoId) {
        Conexao conexao = repo.buscarConexao(estabelecimentoId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Clínica sem conexão com a Meta"));
        if (conexao.tokenCifrado() != null) {
            try {
                graph.delete("/me/permissions", crypto.decifrar(conexao.tokenCifrado()));
            } catch (RuntimeException e) {
                // Revogar na Meta é melhor esforço: o token é apagado aqui de qualquer forma
                log.warn("Não foi possível revogar a permissão na Meta (estabelecimento={}): {}",
                        estabelecimentoId, e.getMessage());
            }
        }
        repo.marcarStatus(estabelecimentoId, MetaRepository.REVOGADA, "Desconectado pelo usuário");
        auditoria.registrar(ENTIDADE_AUDITORIA, estabelecimentoId.toString(), "INATIVACAO");
    }

    // ── auxiliares ────────────────────────────────────────────────────────────

    private JsonNode depurar(String token) {
        return graph.get("/debug_token", Map.of(
                "input_token", token,
                "access_token", props.appId() + "|" + props.appSecret()), null).path("data");
    }

    private static boolean temEscopo(JsonNode info) {
        for (JsonNode escopo : info.path("scopes")) {
            if (ESCOPO_NECESSARIO.equals(escopo.asText())) {
                return true;
            }
        }
        return false;
    }

    private static String escopos(JsonNode info) {
        var lista = new java.util.ArrayList<String>();
        info.path("scopes").forEach(e -> lista.add(e.asText()));
        return String.join(",", lista);
    }

    private EstadoOAuth consumirState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String valor = redis.opsForValue().getAndDelete(PREFIXO_STATE + state);
        if (valor == null) {
            return null;
        }
        try {
            return mapper.readValue(valor, EstadoOAuth.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    String retornoFront(String estabelecimentoId, String resultado, String motivo) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("meta", resultado);
        if (estabelecimentoId != null) {
            params.put("estabelecimento", estabelecimentoId);
        }
        if (motivo != null) {
            params.put("motivo", motivo);
        }
        var url = UriComponentsBuilder.fromUriString(props.frontRetornoUrl());
        params.forEach(url::queryParam);
        return url.encode().build().toUriString();
    }

    private String json(EstadoOAuth estado) {
        try {
            return mapper.writeValueAsString(estado);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar state OAuth", e);
        }
    }

    static class PermissaoInsuficienteException extends RuntimeException {
        PermissaoInsuficienteException() {
            super("O usuário não concedeu a permissão " + ESCOPO_NECESSARIO);
        }
    }
}

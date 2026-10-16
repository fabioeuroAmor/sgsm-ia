package br.com.sgsm.ia.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Trava distribuída simples no Redis (SET NX com TTL): vale entre réplicas do sgsm-ia, ao
// contrário de um AtomicBoolean em memória. O TTL libera a trava sozinho se a instância que a
// pegou morrer no meio da operação.
@Component
public class TravaRedis {

    // Só apaga a chave se ela ainda pertence a quem pegou a trava — evita que uma execução que
    // passou do TTL libere a trava de outra execução que já começou
    private static final RedisScript<Long> LIBERAR_SE_DONO = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public TravaRedis(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // Token do dono da trava, ou vazio se outra execução já a detém
    public Optional<String> adquirir(String chave, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean adquirida = redis.opsForValue().setIfAbsent(chave, token, ttl);
        return Boolean.TRUE.equals(adquirida) ? Optional.of(token) : Optional.empty();
    }

    public void liberar(String chave, String token) {
        redis.execute(LIBERAR_SE_DONO, List.of(chave), token);
    }
}

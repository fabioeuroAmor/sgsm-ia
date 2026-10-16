package br.com.sgsm.ia.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TravaRedisTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valores;

    private TravaRedis trava;

    @BeforeEach
    void setUp() {
        trava = new TravaRedis(redis);
    }

    @Test
    void adquirirComChaveLivreDeveDevolverTokenGravadoComTtl() {
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.setIfAbsent(eq("chave"), anyString(), eq(TTL))).thenReturn(true);

        var token = trava.adquirir("chave", TTL);

        assertThat(token).isPresent();
        verify(valores).setIfAbsent("chave", token.get(), TTL);
    }

    @Test
    void adquirirComChaveOcupadaDeveDevolverVazio() {
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.setIfAbsent(eq("chave"), anyString(), eq(TTL))).thenReturn(false);

        assertThat(trava.adquirir("chave", TTL)).isEmpty();
    }

    @Test
    void retornoNuloDoRedisDeveSerTratadoComoOcupado() {
        when(redis.opsForValue()).thenReturn(valores);
        when(valores.setIfAbsent(eq("chave"), anyString(), eq(TTL))).thenReturn(null);

        assertThat(trava.adquirir("chave", TTL)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void liberarDeveApagarSoSeOTokenForODoDono() {
        trava.liberar("chave", "token-1");

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        verify(redis).execute(script.capture(), eq(List.of("chave")), eq("token-1"));
        assertThat(script.getValue().getScriptAsString()).contains("redis.call('get', KEYS[1]) == ARGV[1]");
    }
}

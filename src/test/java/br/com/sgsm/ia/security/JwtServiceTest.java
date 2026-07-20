package br.com.sgsm.ia.security;

import br.com.sgsm.ia.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SEGREDO = "01234567890123456789012345678901";

    private final JwtProperties props = new JwtProperties(SEGREDO);
    private final JwtService jwtService = new JwtService(props);

    private String gerarToken(String secret, String subject) {
        SecretKey chave = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .claim("perfil", "MEDICO")
                .signWith(chave)
                .compact();
    }

    @Test
    void deveValidarTokenAssinadoComMesmoSegredo() {
        String token = gerarToken(SEGREDO, "usuario@sgsm.com");

        assertThat(jwtService.tokenValido(token)).isTrue();
        assertThat(jwtService.extrairClaims(token).getSubject()).isEqualTo("usuario@sgsm.com");
    }

    @Test
    void deveInvalidarTokenAssinadoComSegredoDiferente() {
        String token = gerarToken("99999999999999999999999999999999", "usuario@sgsm.com");

        assertThat(jwtService.tokenValido(token)).isFalse();
    }

    @Test
    void deveInvalidarTokenMalFormado() {
        assertThat(jwtService.tokenValido("token-invalido")).isFalse();
    }
}

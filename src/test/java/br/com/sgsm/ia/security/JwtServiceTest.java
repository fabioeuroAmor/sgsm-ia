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
        return gerarToken(secret, subject, "sgsm-auth", "sgsm-ia");
    }

    private String gerarToken(String secret, String subject, String emissor, String audiencia) {
        SecretKey chave = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        var builder = Jwts.builder()
                .subject(subject)
                .claim("perfil", "MEDICO");
        if (emissor != null) {
            builder.issuer(emissor);
        }
        if (audiencia != null) {
            builder.audience().add(audiencia);
        }
        return builder.signWith(chave).compact();
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

    // Mesmo segredo, mas sem iss/aud: emitido antes desta versão ou por outro sistema
    @Test
    void deveInvalidarTokenSemIssuerEAudiencia() {
        assertThat(jwtService.tokenValido(gerarToken(SEGREDO, "u", null, null))).isFalse();
    }

    @Test
    void deveInvalidarTokenDeOutroEmissor() {
        assertThat(jwtService.tokenValido(gerarToken(SEGREDO, "u", "outro-sistema", "sgsm-ia"))).isFalse();
    }

    // Token emitido só para o sgsm não vale no sgsm-ia
    @Test
    void deveInvalidarTokenSemSgsmIaNaAudiencia() {
        assertThat(jwtService.tokenValido(gerarToken(SEGREDO, "u", "sgsm-auth", "sgsm"))).isFalse();
    }
}

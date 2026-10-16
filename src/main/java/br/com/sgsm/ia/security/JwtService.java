package br.com.sgsm.ia.security;

import br.com.sgsm.ia.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

@Service
public class JwtService {

    // O segredo HMAC é compartilhado com ms-sboot-auth e sgsm: além da assinatura, exige o
    // emissor (ms-sboot-auth) e "sgsm-ia" no aud — token sem esses claims é rejeitado
    public static final String EMISSOR_PADRAO = "sgsm-auth";
    public static final String AUDIENCIA_PADRAO = "sgsm-ia";

    private final SecretKey chave;
    private final String emissor;
    private final String audiencia;

    public JwtService(JwtProperties props) {
        this(props, EMISSOR_PADRAO, AUDIENCIA_PADRAO);
    }

    @Autowired
    public JwtService(JwtProperties props,
                      @Value("${jwt.issuer:" + EMISSOR_PADRAO + "}") String emissor,
                      @Value("${jwt.audiencia:" + AUDIENCIA_PADRAO + "}") String audiencia) {
        this.chave = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.emissor = emissor;
        this.audiencia = audiencia;
    }

    public Claims extrairClaims(String token) {
        return Jwts.parser()
                .verifyWith(chave)
                .requireIssuer(emissor)
                .requireAudience(audiencia)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean tokenValido(String token) {
        try {
            extrairClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}

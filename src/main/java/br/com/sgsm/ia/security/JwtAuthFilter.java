package br.com.sgsm.ia.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BLACKLIST_PREFIX = "blacklist:";

    private final JwtService jwtService;
    private final StringRedisTemplate redis;

    public JwtAuthFilter(JwtService jwtService, StringRedisTemplate redis) {
        this.jwtService = jwtService;
        this.redis = redis;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        if (!jwtService.tokenValido(token)) {
            chain.doFilter(request, response);
            return;
        }

        Claims claims = jwtService.extrairClaims(token);

        String jti = claims.getId();
        if (jti != null && Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti))) {
            chain.doFilter(request, response);
            return;
        }

        List<String> roles = claims.get("roles", List.class);
        List<SimpleGrantedAuthority> authorities = roles == null ? List.of() :
                roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();

        var auth = new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);

        request.setAttribute("referenciaId", claims.get("referenciaId", String.class));
        request.setAttribute("perfil", claims.get("perfil", String.class));
        request.setAttribute("email", claims.get("email", String.class));

        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }
}

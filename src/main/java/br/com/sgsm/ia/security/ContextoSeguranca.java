package br.com.sgsm.ia.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class ContextoSeguranca {

    public String getUsuarioId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    public String getPerfil() {
        return atributoRequest("perfil");
    }

    public String getReferenciaIdStr() {
        return atributoRequest("referenciaId");
    }

    public boolean isMedico() {
        return temRole("ROLE_MEDICO");
    }

    public boolean isPaciente() {
        return temRole("ROLE_PACIENTE");
    }

    private boolean temRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(role));
    }

    private String atributoRequest(String nome) {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            Object val = sra.getRequest().getAttribute(nome);
            return val != null ? val.toString() : null;
        }
        return null;
    }
}

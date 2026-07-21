package br.com.sgsm.ia.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextoSegurancaTest {

    private ContextoSeguranca contexto;

    @BeforeEach
    void setUp() {
        contexto = new ContextoSeguranca();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getUsuarioIdDeveRetornarSubjectDoJwt() {
        var auth = new UsernamePasswordAuthenticationToken("usuario-uuid-123", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(contexto.getUsuarioId()).isEqualTo("usuario-uuid-123");
    }

    @Test
    void getUsuarioIdSemAutenticacaoDeveRetornarNulo() {
        assertThat(contexto.getUsuarioId()).isNull();
    }

    @Test
    void getPerfilDeveRetornarAtributoDoRequest() {
        var request = new MockHttpServletRequest();
        request.setAttribute("perfil", "MEDICO");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(contexto.getPerfil()).isEqualTo("MEDICO");
    }

    @Test
    void getPerfilSemRequestDeveRetornarNulo() {
        assertThat(contexto.getPerfil()).isNull();
    }

    @Test
    void getReferenciaIdStrDeveRetornarAtributoDoRequest() {
        var request = new MockHttpServletRequest();
        request.setAttribute("referenciaId", "referencia-uuid-456");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(contexto.getReferenciaIdStr()).isEqualTo("referencia-uuid-456");
    }

    @Test
    void isMedicoDeveRetornarTrueQuandoTemRoleCorreta() {
        var auth = new UsernamePasswordAuthenticationToken(
                "usuario", null, List.of(new SimpleGrantedAuthority("ROLE_MEDICO")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(contexto.isMedico()).isTrue();
        assertThat(contexto.isPaciente()).isFalse();
    }

    @Test
    void isPacienteDeveRetornarTrueQuandoTemRoleCorreta() {
        var auth = new UsernamePasswordAuthenticationToken(
                "usuario", null, List.of(new SimpleGrantedAuthority("ROLE_PACIENTE")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(contexto.isPaciente()).isTrue();
        assertThat(contexto.isMedico()).isFalse();
    }

    @Test
    void isMedicoSemAutenticacaoDeveRetornarFalse() {
        assertThat(contexto.isMedico()).isFalse();
        assertThat(contexto.isPaciente()).isFalse();
    }
}

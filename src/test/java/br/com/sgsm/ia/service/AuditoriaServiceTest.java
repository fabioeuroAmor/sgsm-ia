package br.com.sgsm.ia.service;

import br.com.sgsm.ia.security.ContextoSeguranca;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditoriaServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private ContextoSeguranca contexto;

    @InjectMocks
    private AuditoriaService auditoriaService;

    @Test
    void deveGravarLeituraComDadosDoUsuarioAutenticado() {
        when(contexto.getReferenciaIdStr()).thenReturn("medico-uuid");
        when(contexto.getPerfil()).thenReturn("MEDICO");
        when(contexto.getEmail()).thenReturn("dr@sgsm.com");

        auditoriaService.registrarLeitura("NOTA_CLINICA", "paciente-uuid");

        verify(jdbc).update(contains("INSERT INTO sgsm.log_acesso"),
                eq("medico-uuid"), eq("MEDICO"), eq("dr@sgsm.com"), eq("NOTA_CLINICA"), eq("paciente-uuid"), eq("LEITURA"));
    }

    @Test
    void deveGravarAcaoComAutorInformadoQuandoNaoHaUsuarioNaRequisicao() {
        auditoriaService.registrar("META_CONEXAO", "estab-uuid", "CRIACAO", "admin-uuid", "ADMIN_ESTABELECIMENTO", "a@sgsm.com");

        verify(jdbc).update(contains("INSERT INTO sgsm.log_acesso"), eq("admin-uuid"), eq("ADMIN_ESTABELECIMENTO"),
                eq("a@sgsm.com"), eq("META_CONEXAO"), eq("estab-uuid"), eq("CRIACAO"));
        verifyNoInteractions(contexto);
    }

    // Fail closed: sem auditoria, a leitura do dado sensível não acontece
    @Test
    void falhaAoGravarDevePropagar() {
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("db fora"));

        assertThatThrownBy(() -> auditoriaService.registrarLeitura("PACIENTE_360", "paciente-uuid"))
                .isInstanceOf(RuntimeException.class);
    }
}

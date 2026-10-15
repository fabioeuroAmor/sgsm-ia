package br.com.sgsm.ia.service;

import br.com.sgsm.ia.security.ContextoSeguranca;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// Registra em sgsm.log_acesso (V007) a leitura de dados sensíveis de paciente feita pelo
// sgsm-ia — mesmo formato do AuditoriaService do sgsm (usuario_id = referenciaId do JWT).
// Falha de gravação propaga (fail closed): sem auditoria, sem leitura.
@Service
public class AuditoriaService {

    private final JdbcTemplate jdbc;
    private final ContextoSeguranca contexto;

    public AuditoriaService(JdbcTemplate jdbc, ContextoSeguranca contexto) {
        this.jdbc = jdbc;
        this.contexto = contexto;
    }

    public void registrarLeitura(String entidade, String entidadeId) {
        jdbc.update("""
                INSERT INTO sgsm.log_acesso (usuario_id, perfil, email, entidade, entidade_id, acao)
                VALUES (?::uuid, ?, ?, ?, ?::uuid, 'LEITURA')
                """,
                contexto.getReferenciaIdStr(), contexto.getPerfil(), contexto.getEmail(), entidade, entidadeId);
    }
}

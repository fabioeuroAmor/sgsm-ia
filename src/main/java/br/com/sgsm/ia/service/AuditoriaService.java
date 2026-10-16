package br.com.sgsm.ia.service;

import br.com.sgsm.ia.security.ContextoSeguranca;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// Registra em sgsm.log_acesso (V007) acessos e ações sobre dados sensíveis feitos pelo
// sgsm-ia — mesmo formato do AuditoriaService do sgsm (usuario_id = referenciaId do JWT).
// Falha de gravação propaga (fail closed): sem auditoria, sem a operação.
@Service
public class AuditoriaService {

    private final JdbcTemplate jdbc;
    private final ContextoSeguranca contexto;

    public AuditoriaService(JdbcTemplate jdbc, ContextoSeguranca contexto) {
        this.jdbc = jdbc;
        this.contexto = contexto;
    }

    public void registrarLeitura(String entidade, String entidadeId) {
        registrar(entidade, entidadeId, "LEITURA");
    }

    // Ação feita pelo usuário autenticado da requisição atual
    public void registrar(String entidade, String entidadeId, String acao) {
        registrar(entidade, entidadeId, acao, contexto.getReferenciaIdStr(), contexto.getPerfil(), contexto.getEmail());
    }

    // Ação sem usuário na requisição (ex.: callback OAuth, que é a Meta redirecionando o navegador):
    // o autor vem de quem iniciou o fluxo
    public void registrar(String entidade, String entidadeId, String acao, String referenciaId, String perfil, String email) {
        jdbc.update("""
                INSERT INTO sgsm.log_acesso (usuario_id, perfil, email, entidade, entidade_id, acao)
                VALUES (?::uuid, ?, ?, ?, ?::uuid, ?)
                """,
                referenciaId, perfil, email, entidade, entidadeId, acao);
    }
}

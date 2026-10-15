package br.com.sgsm.ia.security;

import br.com.sgsm.ia.exception.AcessoNegadoException;
import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Equivalente, no sgsm-ia, ao EstabelecimentoAcessoService do sgsm: único ponto de verdade
// sobre o que o usuário autenticado pode ver por estabelecimento. O sgsm-ia usa JdbcTemplate
// (sem JPA), então o escopo é expresso como subconsultas SQL que os services encaixam nas
// próprias queries (ex.: "a.medico_id IN (<subconsulta>)").
//
// Diferença proposital em relação ao sgsm: lá, um papel não mapeado (ex.: PACIENTE) recebe
// null = irrestrito, porque o escopo de PACIENTE é feito por titularidade em outra camada.
// Aqui não existe essa segunda camada — papel não mapeado recebe subconsulta vazia (nega tudo).
@Component
public class EscopoAcessoService {

    private static final String ESTABELECIMENTOS_MEDICO =
            "SELECT estabelecimento_id FROM sgsm.medico_estabelecimento WHERE medico_id = ?::uuid AND ativo = true";
    private static final String ESTABELECIMENTOS_FUNCIONARIO =
            "SELECT estabelecimento_id FROM sgsm.funcionario WHERE id = ?::uuid AND ativo = true";
    private static final String ESTABELECIMENTOS_ADMIN =
            "SELECT estabelecimento_id FROM sgsm.administrador_estabelecimento WHERE administrador_id = ?::uuid AND ativo = true";
    private static final String NENHUM = "SELECT NULL::uuid WHERE false";

    // Fragmento SQL (sempre uma subconsulta de uma coluna uuid) + parâmetros posicionais dele
    public record Subconsulta(String sql, List<Object> params) {

        // Concatena parâmetros antes e/ou depois dos da subconsulta, na ordem em que os "?"
        // aparecem na query final
        public Object[] comParametros(Object... antes) {
            var todos = new ArrayList<>(Arrays.asList(antes));
            todos.addAll(params);
            return todos.toArray();
        }
    }

    private final ContextoSeguranca contexto;
    private final JdbcTemplate jdbc;

    public EscopoAcessoService(ContextoSeguranca contexto, JdbcTemplate jdbc) {
        this.contexto = contexto;
        this.jdbc = jdbc;
    }

    // null = irrestrito (DESENVOLVEDOR)
    public Subconsulta estabelecimentos() {
        if (contexto.isDesenvolvedor()) {
            return null;
        }
        String referenciaId = contexto.getReferenciaIdStr();
        if (referenciaId == null) {
            return new Subconsulta(NENHUM, List.of());
        }
        if (contexto.isMedico()) {
            return new Subconsulta(ESTABELECIMENTOS_MEDICO, List.of(referenciaId));
        }
        if (contexto.isFuncionario()) {
            return new Subconsulta(ESTABELECIMENTOS_FUNCIONARIO, List.of(referenciaId));
        }
        if (contexto.isAdminEstabelecimento()) {
            return new Subconsulta(ESTABELECIMENTOS_ADMIN, List.of(referenciaId));
        }
        return new Subconsulta(NENHUM, List.of());
    }

    // Médicos vinculados (ativos) a algum estabelecimento permitido. É a base do escopo de
    // paciente — via agendamento.medico_id, não agendamento.estabelecimento_id, que fica nulo
    // em agendamento domiciliar. null = irrestrito.
    public Subconsulta medicos() {
        Subconsulta estabelecimentos = estabelecimentos();
        if (estabelecimentos == null) {
            return null;
        }
        return new Subconsulta(
                "SELECT me.medico_id FROM sgsm.medico_estabelecimento me WHERE me.ativo = true AND me.estabelecimento_id IN ("
                        + estabelecimentos.sql() + ")",
                estabelecimentos.params());
    }

    // Predicado booleano sobre um agendamento (alias informado) para KPIs e documentos de
    // agendamento: presencial conta para o estabelecimento onde ocorreu; domiciliar
    // (estabelecimento_id nulo) conta para as clínicas do médico que atendeu. null = irrestrito.
    public Subconsulta predicadoAgendamento(String alias) {
        Subconsulta estabelecimentos = estabelecimentos();
        if (estabelecimentos == null) {
            return null;
        }
        Subconsulta medicos = medicos();
        var params = new ArrayList<>(estabelecimentos.params());
        params.addAll(medicos.params());
        return new Subconsulta(
                "(" + alias + ".estabelecimento_id IN (" + estabelecimentos.sql() + ") OR ("
                        + alias + ".estabelecimento_id IS NULL AND " + alias + ".medico_id IN (" + medicos.sql() + ")))",
                params);
    }

    // Paciente atendido por algum médico das clínicas do usuário (presencial ou domiciliar)
    public boolean pacienteNoEscopo(String pacienteId) {
        Subconsulta medicos = medicos();
        if (medicos == null) {
            return true;
        }
        return existe("SELECT EXISTS (SELECT 1 FROM sgsm.agendamento a WHERE a.paciente_id = ?::uuid AND a.medico_id IN ("
                + medicos.sql() + "))", medicos.comParametros(pacienteId));
    }

    // Fora do escopo responde 404, não 403, para não confirmar a existência de pacientes de
    // outras clínicas (enumeração por IDOR)
    public void validarPacienteNoEscopo(String pacienteId) {
        if (!pacienteNoEscopo(pacienteId)) {
            throw new RecursoNaoEncontradoException("Paciente não encontrado: " + pacienteId);
        }
    }

    // Notas clínicas (sigilo médico): só o próprio médico que atendeu o paciente, ou DESENVOLVEDOR
    public boolean pacienteAtendidoPeloMedico(String pacienteId) {
        if (contexto.isDesenvolvedor()) {
            return true;
        }
        String referenciaId = contexto.getReferenciaIdStr();
        if (!contexto.isMedico() || referenciaId == null) {
            return false;
        }
        return existe("SELECT EXISTS (SELECT 1 FROM sgsm.agendamento WHERE paciente_id = ?::uuid AND medico_id = ?::uuid)",
                pacienteId, referenciaId);
    }

    public void validarPacienteAtendidoPeloMedico(String pacienteId) {
        if (!pacienteAtendidoPeloMedico(pacienteId)) {
            throw new RecursoNaoEncontradoException("Paciente não encontrado: " + pacienteId);
        }
    }

    public boolean estabelecimentoPermitido(String estabelecimentoId) {
        Subconsulta estabelecimentos = estabelecimentos();
        if (estabelecimentos == null) {
            return true;
        }
        if (estabelecimentoId == null) {
            return false;
        }
        return existe("SELECT ?::uuid IN (" + estabelecimentos.sql() + ")", estabelecimentos.comParametros(estabelecimentoId));
    }

    public void validarEstabelecimentoPermitido(String estabelecimentoId) {
        if (!estabelecimentoPermitido(estabelecimentoId)) {
            throw new AcessoNegadoException("Acesso negado ao estabelecimento: " + estabelecimentoId);
        }
    }

    public boolean existe(String sql, Object... params) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, params));
    }
}

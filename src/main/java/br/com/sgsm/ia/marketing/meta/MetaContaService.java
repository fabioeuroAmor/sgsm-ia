package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.marketing.meta.MetaRepository.Conexao;
import br.com.sgsm.ia.marketing.meta.MetaRepository.ContaAnuncio;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Contas de anúncio que a conexão enxerga; o admin marca quais pertencem à clínica — só essas
// são sincronizadas.
@Service
public class MetaContaService {

    static final String CAMPOS_CONTA = "id,account_id,name,currency,timezone_name,account_status";

    private final MetaGraphClient graph;
    private final MetaRepository repo;
    private final MetaTokenCryptoService crypto;

    public MetaContaService(MetaGraphClient graph, MetaRepository repo, MetaTokenCryptoService crypto) {
        this.graph = graph;
        this.repo = repo;
        this.crypto = crypto;
    }

    // Busca na Meta, atualiza o banco (sem perder a seleção já feita) e devolve a lista
    public List<ContaAnuncio> disponiveis(UUID estabelecimentoId) {
        Conexao conexao = conexaoAtiva(repo, estabelecimentoId);
        String token = crypto.decifrar(conexao.tokenCifrado());
        graph.getTodos("/me/adaccounts", Map.of("fields", CAMPOS_CONTA), token)
                .forEach(conta -> repo.salvarConta(conexao.id(), estabelecimentoId, conta));
        return repo.contas(estabelecimentoId);
    }

    public List<ContaAnuncio> selecionar(UUID estabelecimentoId, Collection<String> actIds) {
        conexaoAtiva(repo, estabelecimentoId);
        var conhecidas = new HashSet<String>();
        repo.contas(estabelecimentoId).forEach(c -> conhecidas.add(c.actId()));
        for (String actId : actIds) {
            if (!conhecidas.contains(actId)) {
                throw new IllegalArgumentException("Conta de anúncio não pertence a esta conexão: " + actId);
            }
        }
        repo.selecionarContas(estabelecimentoId, actIds);
        return repo.contas(estabelecimentoId);
    }

    static Conexao conexaoAtiva(MetaRepository repo, UUID estabelecimentoId) {
        return repo.buscarConexao(estabelecimentoId)
                .filter(c -> MetaRepository.ATIVA.equals(c.status()) && c.tokenCifrado() != null)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Clínica sem conexão ativa com a Meta"));
    }
}

package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.OperacaoEmAndamentoException;
import br.com.sgsm.ia.marketing.meta.MetaRepository.Conexao;
import br.com.sgsm.ia.marketing.meta.MetaRepository.ContaAnuncio;
import br.com.sgsm.ia.service.TravaRedis;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Sincronização (Fase 5): para cada conta selecionada da clínica, campanhas > conjuntos >
// anúncios > insights diários, gravando no banco. O dashboard e o assistente leem do banco.
//  - insights dos últimos N dias reprocessados a cada rodada (a Meta revisa números passados)
//  - para antes do limite de uso da API (cabeçalhos de uso)
//  - token inválido/revogado na Meta marca a conexão como EXPIRADA (front pede reconexão)
@Service
public class MetaSyncService {

    private static final Logger log = LoggerFactory.getLogger(MetaSyncService.class);

    static final String PREFIXO_TRAVA = "sgsm-ia:trava:meta-sync:";
    static final Duration TTL_TRAVA = Duration.ofHours(1);
    static final List<String> NIVEIS = List.of("campaign", "adset", "ad");
    static final String CAMPOS_CAMPANHA = "id,name,status,effective_status,objective,daily_budget,lifetime_budget,start_time,stop_time";
    static final String CAMPOS_CONJUNTO = "id,name,campaign_id,status,effective_status,daily_budget,lifetime_budget";
    static final String CAMPOS_ANUNCIO = "id,name,campaign_id,adset_id,status,effective_status";
    static final String CAMPOS_INSIGHT = "campaign_id,adset_id,ad_id,impressions,reach,clicks,spend,actions,date_start,date_stop";

    public record Resultado(String status, int contas, int campanhas, int conjuntos, int anuncios, int insights,
                            boolean interrompidoPorLimite) {}

    private final MetaProperties props;
    private final MetaGraphClient graph;
    private final MetaRepository repo;
    private final MetaTokenCryptoService crypto;
    private final MetaNormalizador normalizador;
    private final TravaRedis trava;
    private final MetaRagIndexador ragIndexador;
    private final Clock clock;

    public MetaSyncService(MetaProperties props, MetaGraphClient graph, MetaRepository repo,
                           MetaTokenCryptoService crypto, MetaNormalizador normalizador, TravaRedis trava,
                           MetaRagIndexador ragIndexador, Clock clock) {
        this.props = props;
        this.graph = graph;
        this.repo = repo;
        this.crypto = crypto;
        this.normalizador = normalizador;
        this.trava = trava;
        this.ragIndexador = ragIndexador;
        this.clock = clock;
    }

    // Rodada do scheduler: uma clínica que falhar não impede as demais
    public void sincronizarTodas() {
        for (Conexao conexao : repo.conexoesAtivas()) {
            try {
                sincronizar(conexao.estabelecimentoId());
            } catch (RuntimeException e) {
                log.warn("Sync Meta falhou para estabelecimento={}: {}", conexao.estabelecimentoId(), e.getMessage());
            }
        }
    }

    public Resultado sincronizar(UUID estabelecimentoId) {
        String chave = PREFIXO_TRAVA + estabelecimentoId;
        String dono = trava.adquirir(chave, TTL_TRAVA)
                .orElseThrow(() -> new OperacaoEmAndamentoException("Já existe uma sincronização em andamento para esta clínica"));
        try {
            return executar(estabelecimentoId);
        } finally {
            trava.liberar(chave, dono);
        }
    }

    private Resultado executar(UUID estabelecimentoId) {
        Conexao conexao = MetaContaService.conexaoAtiva(repo, estabelecimentoId);
        String token = crypto.decifrar(conexao.tokenCifrado());
        UUID execucao = repo.iniciarSync(estabelecimentoId);
        int campanhas = 0, conjuntos = 0, anuncios = 0, insights = 0, contas = 0;
        boolean interrompido = false;
        try {
            for (ContaAnuncio conta : repo.contasSelecionadas(estabelecimentoId)) {
                if (graph.getUltimoUsoPercentual() >= props.limiteUsoPercentual()) {
                    interrompido = true;
                    log.warn("Uso da API da Meta em {}% — sync interrompido para estabelecimento={}",
                            graph.getUltimoUsoPercentual(), estabelecimentoId);
                    break;
                }
                String act = conta.actId();
                for (JsonNode c : graph.getTodos("/" + act + "/campaigns", Map.of("fields", CAMPOS_CAMPANHA), token)) {
                    repo.salvarCampanha(estabelecimentoId, act, c);
                    campanhas++;
                }
                for (JsonNode c : graph.getTodos("/" + act + "/adsets", Map.of("fields", CAMPOS_CONJUNTO), token)) {
                    repo.salvarConjunto(estabelecimentoId, act, c);
                    conjuntos++;
                }
                for (JsonNode a : graph.getTodos("/" + act + "/ads", Map.of("fields", CAMPOS_ANUNCIO), token)) {
                    repo.salvarAnuncio(estabelecimentoId, act, a);
                    anuncios++;
                }
                for (String nivel : NIVEIS) {
                    for (JsonNode linha : graph.getTodos("/" + act + "/insights", parametrosInsights(nivel), token)) {
                        repo.salvarInsight(estabelecimentoId, normalizador.normalizar(nivel, act, conta.moeda(), linha));
                        insights++;
                    }
                }
                contas++;
            }
        } catch (MetaApiException e) {
            repo.finalizarSync(execucao, "ERRO", campanhas, conjuntos, anuncios, insights, e.getMessage());
            if (e.tokenInvalido()) {
                repo.marcarStatus(estabelecimentoId, MetaRepository.EXPIRADA,
                        "A Meta recusou o token — reconecte a conta Meta Ads");
            }
            throw e;
        }
        repo.finalizarSync(execucao, interrompido ? "PARCIAL" : "SUCESSO", campanhas, conjuntos, anuncios, insights, null);
        repo.registrarSyncConcluido(estabelecimentoId);
        ragIndexador.indexar(estabelecimentoId);
        return new Resultado(interrompido ? "PARCIAL" : "SUCESSO", contas, campanhas, conjuntos, anuncios, insights,
                interrompido);
    }

    Map<String, String> parametrosInsights(String nivel) {
        LocalDate ate = LocalDate.now(clock);
        LocalDate desde = ate.minusDays(props.diasReprocessamento());
        return Map.of(
                "level", nivel,
                "fields", CAMPOS_INSIGHT,
                "time_increment", "1",
                "time_range", "{\"since\":\"" + desde + "\",\"until\":\"" + ate + "\"}");
    }
}

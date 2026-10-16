package br.com.sgsm.ia.service;

import br.com.sgsm.ia.exception.OperacaoEmAndamentoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class EtlSyncService {

    private static final Logger log = LoggerFactory.getLogger(EtlSyncService.class);

    private static final Map<String, String> TIPO_PARA_SQL = Map.of(
            "PACIENTE",        "SELECT id::text FROM sgsm.paciente WHERE ativo = true",
            "MEDICO",          "SELECT id::text FROM sgsm.medico WHERE ativo = true",
            "ESTABELECIMENTO", "SELECT id::text FROM sgsm.estabelecimento WHERE ativo = true",
            "SERVICO_MEDICO",  "SELECT id::text FROM sgsm.servico_medico WHERE ativo = true",
            "AGENDAMENTO",     "SELECT id::text FROM sgsm.agendamento",
            "LEAD",            "SELECT id::text FROM crm.lead"
    );

    static final String CHAVE_TRAVA = "sgsm-ia:trava:etl-sync";
    // Folga generosa: uma reindexação completa pode demorar; o TTL só existe para liberar a
    // trava se a instância cair no meio da sincronização
    static final Duration TTL_TRAVA = Duration.ofHours(2);

    private final JdbcTemplate jdbc;
    private final DocumentoBuilder documentoBuilder;
    private final MilvusIndexService milvusIndexService;
    private final TravaRedis travaRedis;

    public EtlSyncService(JdbcTemplate jdbc,
                          DocumentoBuilder documentoBuilder,
                          MilvusIndexService milvusIndexService,
                          TravaRedis travaRedis) {
        this.jdbc = jdbc;
        this.documentoBuilder = documentoBuilder;
        this.milvusIndexService = milvusIndexService;
        this.travaRedis = travaRedis;
    }

    public void syncAnalitico() {
        try {
            jdbc.execute("REFRESH MATERIALIZED VIEW crm.mv_resumo_executivo");
        } catch (Exception e) {
            log.warn("Falha ao atualizar mv_resumo_executivo: {}", e.getMessage());
        }
        // Cada view de KPI é indexada isoladamente para que a falha de uma não impeça as demais
        indexarAnaliticoSeguro("resumo-analitico", documentoBuilder::construirAnalitico);
        indexarAnaliticoSeguro("faturamento-mensal", documentoBuilder::construirFaturamentoMensal);
        indexarAnaliticoSeguro("ocupacao-agenda", documentoBuilder::construirOcupacaoAgenda);
        indexarAnaliticoSeguro("alto-valor", documentoBuilder::construirAltoValor);
        indexarAnaliticoSeguro("churn-risco", documentoBuilder::construirChurnRisco);
        indexarAnaliticoSeguro("funil-medico", documentoBuilder::construirFunilMedico);
        indexarAnaliticoSeguro("cancelamentos", documentoBuilder::construirCancelamentos);
        log.info("CRM Analítico re-indexado com sucesso");
    }

    private void indexarAnaliticoSeguro(String referenciaId, Supplier<String> construtor) {
        try {
            milvusIndexService.indexarAnalitico(referenciaId, construtor.get());
        } catch (Exception e) {
            log.warn("Falha ao indexar documento analítico '{}': {}", referenciaId, e.getMessage());
        }
    }

    // Ponto de entrada do POST /ia/etl/sync: reindexação é cara (embedding de todo o banco),
    // então só uma execução por vez em todas as réplicas — chamadas concorrentes recebem 409
    public Map<String, Object> sincronizar(String tipo) {
        String token = travaRedis.adquirir(CHAVE_TRAVA, TTL_TRAVA)
                .orElseThrow(() -> new OperacaoEmAndamentoException("Já existe uma sincronização ETL em andamento"));
        try {
            return tipo != null ? syncTipo(tipo) : syncTodos();
        } finally {
            travaRedis.liberar(CHAVE_TRAVA, token);
        }
    }

    public Map<String, Object> syncTodos() {
        int total = 0, erros = 0;
        for (String tipo : TIPO_PARA_SQL.keySet()) {
            var resultado = syncTipo(tipo);
            total += (int) resultado.get("total");
            erros += (int) resultado.get("erros");
        }
        syncAnalitico();
        return Map.of("total", total, "erros", erros);
    }

    public Map<String, Object> syncTipo(String tipo) {
        String sql = TIPO_PARA_SQL.get(tipo.toUpperCase());
        if (sql == null) {
            return Map.of("total", 0, "erros", 0, "erro", "Tipo desconhecido: " + tipo);
        }
        List<String> ids = jdbc.queryForList(sql, String.class);
        int erros = 0;
        for (String id : ids) {
            try {
                String texto = documentoBuilder.construir(tipo, id);
                milvusIndexService.upsert(tipo, id, texto);
            } catch (Exception e) {
                log.warn("ETL falhou para tipo={} id={}: {}", tipo, id, e.getMessage());
                erros++;
            }
        }
        log.info("ETL sync tipo={}: {} registros, {} erros", tipo, ids.size(), erros);
        return Map.of("tipo", tipo, "total", ids.size(), "erros", erros);
    }
}

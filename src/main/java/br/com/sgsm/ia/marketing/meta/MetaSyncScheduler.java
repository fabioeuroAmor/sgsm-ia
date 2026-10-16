package br.com.sgsm.ia.marketing.meta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Sincronização automática do tráfego pago (padrão do CrmAnaliticoScheduler). Sem a integração
// configurada não faz nada. Antes de sincronizar, marca como EXPIRADA a conexão cujo token venceu
// — o front então mostra "Reconectar".
@Component
public class MetaSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MetaSyncScheduler.class);

    private final MetaProperties props;
    private final MetaRepository repo;
    private final MetaSyncService syncService;

    public MetaSyncScheduler(MetaProperties props, MetaRepository repo, MetaSyncService syncService) {
        this.props = props;
        this.repo = repo;
        this.syncService = syncService;
    }

    @Scheduled(fixedDelayString = "${meta.sync-intervalo-ms:10800000}",
               initialDelayString = "${meta.sync-atraso-inicial-ms:300000}")
    public void sincronizar() {
        if (!props.configurado()) {
            return;
        }
        int expiradas = repo.marcarExpiradas();
        if (expiradas > 0) {
            log.info("{} conexão(ões) com a Meta expiraram e precisam ser reconectadas", expiradas);
        }
        syncService.sincronizarTodas();
    }
}

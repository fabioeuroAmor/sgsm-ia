package br.com.sgsm.ia.scheduler;

import br.com.sgsm.ia.service.EtlSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CrmAnaliticoScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrmAnaliticoScheduler.class);

    private final EtlSyncService etlSyncService;

    public CrmAnaliticoScheduler(EtlSyncService etlSyncService) {
        this.etlSyncService = etlSyncService;
    }

    // Atualiza KPIs analíticos a cada 30 minutos
    @Scheduled(fixedRateString = "${crm.analitico.sync-interval-ms:1800000}")
    public void sincronizarAnalitico() {
        log.info("Iniciando sync automático do CRM Analítico");
        etlSyncService.syncAnalitico();
    }
}

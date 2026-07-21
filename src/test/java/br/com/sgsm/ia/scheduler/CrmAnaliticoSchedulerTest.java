package br.com.sgsm.ia.scheduler;

import br.com.sgsm.ia.service.EtlSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CrmAnaliticoSchedulerTest {

    @Mock
    private EtlSyncService etlSyncService;

    @InjectMocks
    private CrmAnaliticoScheduler scheduler;

    @Test
    void sincronizarAnaliticoDeveDelegarParaEtlSyncService() {
        scheduler.sincronizarAnalitico();

        verify(etlSyncService).syncAnalitico();
    }
}

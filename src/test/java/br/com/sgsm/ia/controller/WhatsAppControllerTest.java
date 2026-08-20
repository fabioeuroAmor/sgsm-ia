package br.com.sgsm.ia.controller;

import br.com.sgsm.ia.dto.IntentWhatsApp;
import br.com.sgsm.ia.dto.WhatsAppClassificarRequest;
import br.com.sgsm.ia.dto.WhatsAppClassificarResponse;
import br.com.sgsm.ia.service.WhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppControllerTest {

    @Mock
    private WhatsAppService whatsAppService;

    private WhatsAppController controller;

    @BeforeEach
    void setUp() {
        controller = new WhatsAppController(whatsAppService);
    }

    @Test
    void deveDelegarClassificacaoParaOServico() {
        var request = new WhatsAppClassificarRequest("quero agendar", null, "PACIENTE", null);
        var respostaEsperada = WhatsAppClassificarResponse.builder()
                .intent(IntentWhatsApp.AGENDAR)
                .respostaUsuario("Vamos agendar!")
                .requerConfirmacao(false)
                .build();
        when(whatsAppService.classificar(request)).thenReturn(respostaEsperada);

        var response = controller.classificar(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(respostaEsperada);
    }
}

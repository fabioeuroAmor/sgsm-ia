package br.com.sgsm.ia.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhatsAppClassificarResponse {

    private IntentWhatsApp intent;
    private EntidadesWhatsApp entidades;
    private String respostaUsuario;
    private boolean requerConfirmacao;
    private List<String> dadosFaltantes;
}

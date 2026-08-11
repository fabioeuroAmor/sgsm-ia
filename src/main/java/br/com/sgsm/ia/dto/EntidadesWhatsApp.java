package br.com.sgsm.ia.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class EntidadesWhatsApp {

    private String especialidade;
    private String data;
    private String hora;
    private String nomeMedico;
    private String nomePaciente;
    private String medicoId;
    private String servicoMedicoId;
    private String agendamentoId;
    private String email;
    private String cpf;
    private String dataNascimento;
    private String nomeCompleto;
}

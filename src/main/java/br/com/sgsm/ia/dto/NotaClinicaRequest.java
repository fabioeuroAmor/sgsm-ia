package br.com.sgsm.ia.dto;

public record NotaClinicaRequest(
        String tipo,
        String conteudo,
        String agendamentoId
) {}

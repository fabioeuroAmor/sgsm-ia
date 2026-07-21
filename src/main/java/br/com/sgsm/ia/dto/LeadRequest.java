package br.com.sgsm.ia.dto;

public record LeadRequest(
        String nome,
        String email,
        String telefone,
        String interesse,
        String origem,
        String observacoes
) {}

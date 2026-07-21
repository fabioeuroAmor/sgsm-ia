package br.com.sgsm.ia.dto;

public record ContatoRequest(
        String tipo,
        String direcao,
        String descricao,
        Integer duracaoSegundos
) {}

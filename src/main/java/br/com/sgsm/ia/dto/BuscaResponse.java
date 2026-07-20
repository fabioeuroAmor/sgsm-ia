package br.com.sgsm.ia.dto;

import java.util.List;

public record BuscaResponse(List<DocumentoDto> documentos) {
    public record DocumentoDto(String tipo, String referenciaId, String conteudo, double score) {}
}

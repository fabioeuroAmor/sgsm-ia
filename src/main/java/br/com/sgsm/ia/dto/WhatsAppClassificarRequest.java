package br.com.sgsm.ia.dto;

import java.util.List;

public record WhatsAppClassificarRequest(
        String mensagem,
        List<MensagemHistorico> historico,
        String perfil,
        String confirmacaoPendente
) {}

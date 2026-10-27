package br.com.sgsm.ia.dto;

import java.util.List;

// estabelecimentoId/estabelecimentoNome: clínica do número de WhatsApp que recebeu a mensagem (vem
// da instância, nunca do texto); nulos no canal geral do SGSM
public record WhatsAppClassificarRequest(
        String mensagem,
        List<MensagemHistorico> historico,
        String perfil,
        String confirmacaoPendente,
        String estabelecimentoId,
        String estabelecimentoNome
) {
    public WhatsAppClassificarRequest(String mensagem, List<MensagemHistorico> historico, String perfil,
                                      String confirmacaoPendente) {
        this(mensagem, historico, perfil, confirmacaoPendente, null, null);
    }
}

package br.com.sgsm.ia.marketing.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;

// Configuração e respostas de exemplo da Graph API usadas nos testes. As respostas seguem o
// formato documentado da Meta; quando a prova de conceito (Fase 1) rodar com a conta da equipe,
// substituir pelos JSONs reais.
final class MetaFixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String APP_SECRET = "segredo-do-app";
    static final String CHAVE_TOKEN = Base64.getEncoder().encodeToString("chave-teste-de-32-bytes-exatos!!".getBytes());

    private MetaFixtures() {
    }

    static MetaProperties props(String graphBaseUrl) {
        return new MetaProperties(true, "app-123", APP_SECRET, "config-456", "v23.0", graphBaseUrl,
                "https://www.facebook.com", "http://localhost:3001/marketing/meta/callback",
                "http://localhost:3001/trafego-pago", CHAVE_TOKEN, 28, 80);
    }

    static MetaProperties props() {
        return props("https://graph.facebook.com");
    }

    static JsonNode json(String texto) {
        try {
            return MAPPER.readTree(texto);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static final String INSIGHT_CAMPANHA = """
            {"campaign_id":"120001","impressions":"10000","reach":"7000","clicks":"250","spend":"500.00",
             "actions":[{"action_type":"link_click","value":"240"},{"action_type":"lead","value":"20"},
                        {"action_type":"onsite_conversion.lead_grouped","value":"12"}],
             "date_start":"2026-10-01","date_stop":"2026-10-01"}
            """;
}

package br.com.sgsm.ia.guardrail;

import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EscopoGuardrail implements InputGuardrail {

    private static final List<String> TERMOS_PERMITIDOS = List.of(
            "paciente", "medico", "médico", "agendamento", "consulta",
            "pagamento", "servico", "serviço", "clinica", "clínica",
            "especialidade", "cancelamento", "horario", "horário",
            "historico", "histórico", "atendimento", "reembolso",
            "estabelecimento", "nota", "prontuario", "prontuário",
            // CRM Analítico / KPIs (resumo executivo indexado no Milvus)
            "receita", "faturamento", "kpi", "ticket medio", "ticket médio",
            "taxa de conversao", "taxa de conversão", "conversao", "conversão",
            "churn", "no-show", "no show", "ocupacao", "ocupação",
            "funil", "lead", "crm"
    );

    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String texto = userMessage.singleText().toLowerCase();
        boolean noEscopo = TERMOS_PERMITIDOS.stream().anyMatch(texto::contains);
        if (!noEscopo) {
            return failure(
                    "Só respondo perguntas relacionadas a pacientes, médicos, agendamentos " +
                    "e dados clínicos do sistema SGSM."
            );
        }
        return success();
    }
}

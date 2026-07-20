# SGSM-IA — Microserviço de Inteligência Artificial

Microserviço construído com **Spring Boot 4** e **Java 21** responsável pela camada de IA da plataforma SGSM: assistente médico conversacional (RAG), busca semântica, KPIs consolidados e sincronização de vetores. Consome eventos do `sgsm` via Redis Streams, indexa embeddings no Milvus e usa LangChain4j para orquestrar o LLM. Faz parte da plataforma SGSM, junto com o [`sgsm`](../sgsm) (backend principal) e o [`sgsm-auth`](../sgsm-auth) (autenticação/autorização).

---

## Tecnologias

| Tecnologia | Versão |
|---|---|
| Java | 21 |
| Spring Boot | 4.0.6 |
| LangChain4j (Spring Boot Starter + OpenAI + Milvus) | 0.36.2 |
| Milvus SDK Java | 2.4.9 |
| Spring Data JPA / JDBC | (Boot Managed) |
| PostgreSQL | (schema `sgsm` + `crm`) |
| Redis (Streams) | (Boot Managed) |
| Lombok | (Boot Managed) |
| Jackson Databind | 2.15.2 |
| SpringDoc OpenAPI (Swagger) | 2.8.6 |
| JJWT (validação de JWT) | 0.12.6 |
| Jacoco | 0.8.12 |

---

## Pré-requisitos

- **JDK 21** instalado e no `PATH`
- **PostgreSQL** rodando em `localhost:5432`, com os schemas `sgsm` (dados transacionais, mesmo banco do serviço [`sgsm`](../sgsm)) e `crm` (documentos indexados, materialized views de KPI)
- **Redis** rodando em `localhost:6379` — usado tanto para o stream de eventos de vetorização quanto para a blacklist de tokens JWT
- **Milvus** rodando em `localhost:19530` — vector store dos embeddings
- Uma **API key da OpenAI** (ou provider compatível) para o `ChatModel`/`EmbeddingModel` do LangChain4j
- O serviço **`sgsm-auth`** — é ele quem emite os tokens JWT que este serviço valida

---

## Configuração

`src/main/resources/application.yaml`:

```yaml
server:
  port: 8082

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres?stringtype=unspecified
    username: postgres
    password: postgres
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

jwt:
  secret: ${JWT_SECRET:sgsm-chave-desenvolvimento-local-minimo-256-bits-nao-usar-em-producao}

milvus:
  host: ${MILVUS_HOST:localhost}
  port: ${MILVUS_PORT:19530}
  collection: sgsm_documentos
  embedding-dimension: ${MILVUS_EMBEDDING_DIM:1536}

langchain4j:
  open-ai:
    chat-model:
      api-key: ${OPENAI_API_KEY:demo}
      model-name: ${OPENAI_CHAT_MODEL:gpt-4o-mini}
    embedding-model:
      api-key: ${OPENAI_API_KEY:demo}
      model-name: ${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}

ia:
  provider: ${IA_PROVIDER:openai}
  top-k: 10
  redis:
    stream-key: sgsm:events:vetorizacao
    group: sgsm-ia-group
    consumer: sgsm-ia-consumer-1
```

Altere as credenciais e o `JWT_SECRET` conforme o seu ambiente. **O `jwt.secret` precisa ser idêntico ao configurado no `sgsm-auth`** — é o mesmo segredo HS256 usado para assinar (lá) e validar (aqui) o token.

---

## Como executar

```bash
# Build e execução
./mvnw spring-boot:run

# Somente build
./mvnw clean package

# Executar o JAR gerado
java -jar target/sgsm-ia-0.0.1-SNAPSHOT.jar
```

A API fica disponível em `http://localhost:8082`.

Documentação interativa (Swagger UI): `http://localhost:8082/swagger-ui/index.html`

---

## Autenticação

Este serviço **não emite** tokens — ele só valida os JWTs emitidos pelo `sgsm-auth` (mesmo segredo HS256, algoritmo `jjwt`). Fluxo:

1. Login no `sgsm-auth` → retorna `accessToken`.
2. Envie esse token em toda chamada a este serviço: `Authorization: Bearer <accessToken>`.
3. O `JwtAuthFilter` valida o token, checa a blacklist no Redis (`blacklist:<jti>`) e popula o contexto de segurança com `roles`, `referenciaId`, `perfil` e `email` extraídos das claims.
4. Rotas públicas: `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`. Qualquer outra rota exige autenticação.

---

## Estrutura do projeto

```
src/main/java/br/com/sgsm/ia/
├── controller/       # Endpoints REST (IaController)
├── service/          # Assistente médico (RAG), ETL, KPIs, indexação Milvus
├── consumer/         # Consumer do Redis Stream de eventos de vetorização
├── guardrail/        # Guardrails de input/output do assistente (escopo e sanitização)
├── security/         # Filtro e serviço de validação de JWT
├── dto/              # Requests e Responses
├── exception/        # Handler global de erros (ProblemDetail)
└── config/           # Milvus, Security, OpenAPI, propriedades (@ConfigurationProperties)
```

---

## Endpoints

Base path: `/ia`

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/ia/chat` | Conversa com o assistente médico (RAG sobre os documentos indexados) |
| `GET` | `/ia/busca?q=&tipo=` | Busca semântica nos documentos indexados no Milvus |
| `GET` | `/ia/paciente/{id}/resumo` | Resumo inteligente de um paciente (histórico, LTV, últimas consultas) |
| `GET` | `/ia/kpis` | KPIs consolidados (resumo executivo, faturamento, ocupação, churn, funil, cancelamentos) |
| `POST` | `/ia/etl/sync?tipo=` | Reindexação completa de todas as entidades (ou de um `tipo` específico) no Milvus |

### Exemplo — `POST /ia/chat`

```json
{
  "pergunta": "Qual o histórico de consultas do paciente João da Silva?"
}
```

```json
{
  "resposta": "..."
}
```

---

## Assistente médico (RAG) e Guardrails

`AssistenteMedicoService.responder(pergunta)` orquestra o fluxo em camadas:

1. **`EscopoGuardrail`** (input) — bloqueia perguntas fora do domínio SGSM (verifica se o texto contém termos do domínio: paciente, médico, agendamento, etc.).
2. **Busca semântica no Milvus** — recupera os `top-k` documentos mais relevantes (`ia.top-k`).
3. **Montagem do prompt** — injeta o contexto recuperado isoladamente, com instruções para nunca revelar CPF completo, senhas ou tokens.
4. **Chamada ao LLM** (`ChatLanguageModel`, LangChain4j + OpenAI).
5. **`SanitizacaoGuardrail`** (output) — mascara qualquer CPF que apareça na resposta antes de devolvê-la.

> `EscopoGuardrail` e `SanitizacaoGuardrail` implementam interfaces próprias do projeto (`br.com.sgsm.ia.guardrail.InputGuardrail`/`OutputGuardrail`), não a SPI `dev.langchain4j.guardrail` — que só existe a partir da linha 1.x do LangChain4j (este projeto está na `0.36.2`). São chamados manualmente no `AssistenteMedicoService`, sem uso de `AiServices`/`@InputGuardrails`.
>
> **Limitação conhecida:** o `EscopoGuardrail` é um filtro por palavra-chave — não detecta tentativas de *prompt injection* (ex.: instruções para ignorar o system prompt), apenas limita o tema da pergunta.

---

## Indexação e sincronização (ETL)

- **`VetorizacaoConsumer`** consome o Redis Stream `sgsm:events:vetorizacao` (grupo `sgsm-ia-group`), publicado pelo `sgsm` a cada criação/atualização de entidade. Para cada evento, monta o texto do documento (`DocumentoBuilder`) e faz upsert do embedding no Milvus (`MilvusIndexService`). Falhas não recebem ACK e são reprocessadas automaticamente.
- **`EtlSyncService`** permite reindexação completa sob demanda (`POST /ia/etl/sync`), útil para popular o Milvus pela primeira vez ou recuperar de uma janela de indexação perdida. Tipos suportados: `PACIENTE`, `MEDICO`, `ESTABELECIMENTO`, `SERVICO_MEDICO`, `AGENDAMENTO`.
- Documentos indexados e seu status (`INDEXADO`/`ERRO`, tentativas, versão) são rastreados na tabela `crm.documento`.

---

## KPIs

`KpiService` consulta materialized views/views do schema `crm` (mantidas pelo pipeline de dados do SGSM): `mv_resumo_executivo`, `v_faturamento_mensal`, `v_ocupacao_agenda`, `v_alto_valor`, `v_churn_risco`, `v_funil_medico`, `v_cancelamentos`. `GET /ia/kpis` retorna tudo consolidado num único payload.

---

## Tratamento de erros

Respostas de erro seguem `ProblemDetail` (RFC 7807):

```json
{
  "type": "https://sgsm.com.br/erros/escopo-invalido",
  "title": "Pergunta fora do escopo",
  "status": 422,
  "detail": "Só respondo perguntas relacionadas a pacientes, médicos, agendamentos e dados clínicos do sistema SGSM.",
  "instance": "/ia/chat"
}
```

| HTTP | Situação |
|---|---|
| `422` | Pergunta fora do escopo do assistente (`EscopoInvalidoException`) |
| `404` | Recurso não encontrado (`RecursoNaoEncontradoException`) |
| `400` | Argumento inválido |
| `500` | Erro interno |

---

## Testes e qualidade

```bash
# Rodar a suite de testes
./mvnw test

# Rodar tests + checks de build (inclui gate de cobertura Jacoco: 80% de linhas)
./mvnw verify
```

O `jacoco-maven-plugin` exige mínimo de 80% de cobertura de linha no `verify`, excluindo `SgsmIaApplication`, `dto/**`, `config/**` e `exception/*Exception.class`.

### Verificar árvore de dependências

```bash
./mvnw dependency:tree -DoutputFile=dependencias.txt -Dverbose
```

---

### Clonar o repositório

```bash
git clone <url-do-repositorio>
cd sgsm-ia
./mvnw spring-boot:run
```

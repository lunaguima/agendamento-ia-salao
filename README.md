# Agendamento com I.A.

Atividade da disciplina **Java Advanced (2º semestre) – FIAP**.

Sistema de agendamento para um salão de beleza em que o cliente conversa em linguagem natural com um assistente de IA para **agendar, cancelar e reagendar** serviços. O assistente usa **Tools** (function calling) para executar as ações e **RAG** (vector database) para responder dúvidas com base nas regras do estabelecimento.

- **Aluno:** Luna de Carvalho Guimarães – RM 562290
- **Repositório base:** https://github.com/joaocarloslima/agendamento-ia

---

## Tecnologias

| Camada | Tecnologia |
|---|---|
| Linguagem / Framework | Java, Spring Boot |
| Build | Gradle (wrapper incluído) |
| IA | Spring AI + OpenAI (`gpt-4o-mini`) |
| Embeddings | `text-embedding-3-small` (1536 dimensões) |
| Vector store | PostgreSQL + **pgvector** |
| Persistência | Spring Data JPA (Hibernate, `ddl-auto=update`) |
| Interface | Vaadin Flow |
| Outros | Lombok |

---

## Funcionalidades

- **Chat com IA** (`/chat`): conversa em português do Brasil, com memória das últimas 20 mensagens.
- **Agenda** (`/agenda`): tabela com id, nome, serviço, data, hora e status (`SCHEDULED`, `COMPLETED`, `CANCELED`).
- **Tools**: o modelo chama métodos Java para consultar serviços, agendar, cancelar e reagendar.
- **RAG**: as regras do salão (`regras.pdf`) são carregadas no pgvector e usadas como contexto nas respostas.
- **Seeder**: popula o banco com 3 agendamentos de exemplo na primeira execução.

---

## Arquitetura

```
br.com.fiap.agendamentocomia
├── model         Appointment, AppointmentStatus
├── repository    AppointmentRepository (JpaRepository)
├── service       AppointmentService (CRUD da agenda) e ChatService (ChatClient + advisors)
├── tool          AppointmentTool (ferramentas expostas à IA)
├── rag           RulesLoader (carrega o PDF de regras no vector store)
├── seeder        DatabaseSeeder (dados iniciais)
└── views         MainLayout, HomeView, ChatView, Calendar (Vaadin)
```

### Fluxo do chat

1. O usuário envia a mensagem em `ChatView`.
2. `ChatService` monta o *system prompt* com a data/hora atuais (para interpretar "amanhã", "sexta" etc.).
3. Os *advisors* entram em ação:
   - `MessageChatMemoryAdvisor`: histórico da conversa.
   - `QuestionAnswerAdvisor`: busca no pgvector os trechos de regras relevantes.
4. O modelo decide se chama alguma ferramenta de `AppointmentTool`.
5. A ferramenta valida as regras e persiste no banco; a resposta volta ao chat.

### Tools (`AppointmentTool`)

| Ferramenta | Descrição |
|---|---|
| `getCurrentDate()` | Retorna a data de hoje (`dd/MM/yyyy`). |
| `listServices()` | Lista os serviços e valores. |
| `schedule(name, service, date, time)` | Agenda **um** serviço para o cliente. |
| `cancel(id)` | Cancela um agendamento pelo id. |
| `reschedule(id, date, time)` | Reagenda para nova data/hora. |

### RAG (`RulesLoader`)

Na inicialização (`ApplicationRunner`), o arquivo `src/main/resources/doc/regras.pdf` é lido com `PagePdfDocumentReader`, dividido com `TokenTextSplitter` e gravado no `VectorStore`. Como o pgvector persiste os dados, a carga só acontece se o vector store estiver vazio.

---

## Regras de negócio

### Serviços e valores

| Serviço | Valor (R$) |
|---|---|
| Corte de cabelo feminino | 80 |
| Corte de cabelo masculino | 50 |
| Escova/chapinha | 120 |
| Coloração | 200 |
| Manicure e pedicure | 30 |
| Design de sobrancelhas | 40 |
| Maquiagem | 180 |
| Tratamentos capilares | 190 |

### Agendamento

- Exige **nome completo** (nome e sobrenome).
- O serviço deve ser **exatamente** um da lista; cada agendamento tem **um único serviço** (vários serviços = vários agendamentos).
- Antecedência mínima de **24 horas**.
- Expediente: **segunda a sábado, das 09h às 19h**; sem atendimento aos **domingos e feriados**.
- Não pode haver outro agendamento (`SCHEDULED`) no mesmo dia e horário.
- Após agendar, o cliente recebe a confirmação com os dados e o **ID** do agendamento.

### Cancelamento e remarcação

- Cancelamento com **no mínimo 6 horas** de antecedência; com menos que isso, pode haver **taxa de 50%** do valor do serviço.
- Remarcação sem custos com **no mínimo 6 horas** de antecedência; a nova data/hora segue as mesmas regras de disponibilidade.
- Agendamentos já cancelados ou concluídos não podem ser cancelados/reagendados.

---

## Como executar

### Pré-requisitos

- JDK compatível com o projeto (o Gradle wrapper já está incluído)
- Docker (para o PostgreSQL com pgvector)
- Chave de API da OpenAI

### 1. Clonar o repositório

```bash
git clone https://github.com/lunaguima/agendamento-ia-salao.git
cd agendamento-ia-salao
```

### 2. Configurar a chave da API

A chave é lida da variável de ambiente `OPENAI_API_KEY` (nunca coloque a chave direto no `application.properties`).

```bash
# Linux/macOS
export OPENAI_API_KEY="sua-chave"

# Windows (PowerShell)
$env:OPENAI_API_KEY="sua-chave"
```

### 3. Subir o banco de dados

O projeto possui um `compose.yaml` na raiz que define o PostgreSQL com pgvector (`pgvector/pgvector:pg16`, container `pgvector-agendamento`, porta `5432`, banco `mydatabase`). Para subir:

```bash
docker compose up -d
```

O compose usa o label `org.springframework.boot.service-connection=postgres`, então o Spring Boot detecta a conexão com o container automaticamente. Não é necessário configurar `spring.datasource.*` no `application.properties`.

### 4. Rodar

```bash
# Linux/macOS
./gradlew bootRun

# Windows (PowerShell)
.\gradlew bootRun
```

O navegador abre automaticamente (`vaadin.launch-browser=true`). Rotas:

- `/` – boas-vindas
- `/chat` – chat com o assistente
- `/agenda` – agenda

### Configuração principal (`application.properties`)

```properties
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.chat.options.model=gpt-4o-mini
spring.ai.openai.embedding.options.model=text-embedding-3-small

spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.dimensions=1536

spring.jpa.defer-datasource-initialization=true
spring.jpa.hibernate.ddl-auto=update
```

---

## Exemplos de uso no chat

- "Quais serviços vocês oferecem?"
- "Quero agendar uma manicure e pedicure amanhã às 14h. Meu nome é Maria Oliveira."
- "Cancela o agendamento 4."
- "Reagenda o agendamento 4 para sábado às 10h."
- "Qual a taxa se eu cancelar em cima da hora?"

---

## Limitações conhecidas

- Apenas **feriados nacionais fixos** são considerados; feriados móveis (ex.: Sexta-feira Santa) não estão na lista.
- A verificação de disponibilidade considera apenas o **mesmo dia e horário exatos** (não leva em conta a duração do serviço).
- Os dados do `DatabaseSeeder` são exemplos e usam serviços que não constam na lista oficial (ex.: "Massagem").
- A memória do chat é global (não separada por usuário/sessão).
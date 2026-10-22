package br.com.fiap.agendamentocomia.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import br.com.fiap.agendamentocomia.tool.AppointmentTool;

@Service
public class ChatService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private static final String SYSTEM_PROMPT = """
            Você é um assistente virtual de agendamentos de um salão de beleza.
            Responda sempre em português do Brasil, de forma educada e objetiva.
            Use as ferramentas para consultar serviços, agendar, cancelar e reagendar.

            Para agendar você precisa de: nome completo do cliente, serviço, data e hora.
            Peça o que estiver faltando e não recuse nada antes de ter esses dados.
            O serviço deve ser exatamente um dos oferecidos. Se o cliente pedir algo que o salão não oferece
            ou algo ambíguo (por exemplo "corte de cabelo"), consulte a lista de serviços e pergunte qual ele quer.
            Cada agendamento é de um único serviço. Para vários serviços, crie um agendamento para cada um.

            Chame a ferramenta schedule apenas UMA vez por agendamento, e só depois de ter todos os dados.
            Para cancelar ou reagendar vários agendamentos, chame a ferramenta uma vez para cada id.
            Para datas relativas (hoje, amanhã, sexta, sábado, semana que vem), calcule a partir da data de hoje informada abaixo.

            Quem valida as regras (antecedência, horário, domingos e feriados, disponibilidade) é a ferramenta.
            Se ela devolver uma mensagem de erro, explique o motivo ao cliente e sugira uma alternativa.
            Ao confirmar um agendamento, informe nome, serviço, data, hora e o ID.
            Para dúvidas sobre valores, cancelamento e remarcação, use as regras do estabelecimento fornecidas no contexto.
            """;

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder builder,
                       AppointmentTool appointmentTool,
                       VectorStore vectorStore) {

        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(20).build();

        this.chatClient = builder
                .defaultTools(appointmentTool)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore).build())
                .build();
    }

    public String sendMessage(String message) {
        LocalDate hoje = LocalDate.now();
        String diaSemana = hoje.getDayOfWeek().getDisplayName(TextStyle.FULL, PT_BR);

        return chatClient.prompt()
                .system(SYSTEM_PROMPT
                        + "\nHoje é " + diaSemana + ", " + hoje.format(DATE_FORMAT)
                        + ", agora são " + LocalTime.now().format(TIME_FORMAT)
                        + ". Use SEMPRE essa data e hora como referência.")
                .user(message)
                .call()
                .content();
    }
}
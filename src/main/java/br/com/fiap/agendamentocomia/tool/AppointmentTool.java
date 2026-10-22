package br.com.fiap.agendamentocomia.tool;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import br.com.fiap.agendamentocomia.model.Appointment;
import br.com.fiap.agendamentocomia.model.AppointmentStatus;
import br.com.fiap.agendamentocomia.repository.AppointmentRepository;

@Component
public class AppointmentTool {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    // Expediente: segunda a sábado, 09h às 19h
    private static final LocalTime OPENING = LocalTime.of(9, 0);
    private static final LocalTime CLOSING = LocalTime.of(19, 0);
    private static final long MIN_HOURS_TO_SCHEDULE = 24;
    private static final long MIN_HOURS_TO_CANCEL = 6;

    // Serviços e valores (R$) conforme o regras.pdf
    private static final Map<String, Integer> SERVICES = new LinkedHashMap<>();

    static {
        SERVICES.put("Corte de cabelo feminino", 80);
        SERVICES.put("Corte de cabelo masculino", 50);
        SERVICES.put("Escova/chapinha", 120);
        SERVICES.put("Coloração", 200);
        SERVICES.put("Manicure e pedicure", 30);
        SERVICES.put("Design de sobrancelhas", 40);
        SERVICES.put("Maquiagem", 180);
        SERVICES.put("Tratamentos capilares", 190);
    }

    // Feriados nacionais fixos (os móveis, como Sexta-feira Santa, não estão aqui)
    private static final Set<MonthDay> HOLIDAYS = Set.of(
            MonthDay.of(1, 1), MonthDay.of(4, 21), MonthDay.of(5, 1), MonthDay.of(9, 7),
            MonthDay.of(10, 12), MonthDay.of(11, 2), MonthDay.of(11, 15), MonthDay.of(11, 20),
            MonthDay.of(12, 25));

    private final AppointmentRepository repository;

    public AppointmentTool(AppointmentRepository repository) {
        this.repository = repository;
    }

    @Tool(description = "Retorna a data de hoje no formato dd/MM/yyyy.")
    public String getCurrentDate() {
        return LocalDate.now().format(DATE_FORMAT);
    }

    @Tool(description = "Lista os serviços oferecidos pelo salão com seus valores. Use para responder o que o salão oferece ou quando o cliente pedir um serviço que não existe ou está ambíguo.")
    public String listServices() {
        StringBuilder sb = new StringBuilder("Serviços disponíveis:\n");
        SERVICES.forEach((name, price) -> sb.append("- ").append(name).append(": R$ ").append(price).append("\n"));
        return sb.toString();
    }

    @Tool(description = "Agenda UM serviço para um cliente. O serviço deve ser exatamente um dos retornados por listServices e o nome deve ser completo.")
    public String schedule(
            @ToolParam(description = "Nome completo do cliente") String name,
            @ToolParam(description = "Nome exato do serviço, conforme listServices") String service,
            @ToolParam(description = "Data no formato dd/MM/yyyy") String date,
            @ToolParam(description = "Hora no formato HH:mm") String time) {

        if (name == null || name.trim().split("\\s+").length < 2) {
            return "Preciso do nome completo do cliente (nome e sobrenome) para agendar.";
        }

        String canonicalService = findService(service);
        if (canonicalService == null) {
            return "O serviço '" + service + "' não existe ou não está exato. " + listServices();
        }

        String error = validateSlot(date, time, null);
        if (error != null) {
            return error;
        }

        String d = LocalDate.parse(date, DATE_FORMAT).format(DATE_FORMAT);
        String t = LocalTime.parse(time, TIME_FORMAT).format(TIME_FORMAT);

        var appointment = repository.save(
                new Appointment(null, name.trim(), canonicalService, d, t, AppointmentStatus.SCHEDULED));

        return "Agendamento #" + appointment.getId() + " confirmado: " + name.trim()
                + ", " + canonicalService + " (R$ " + SERVICES.get(canonicalService) + "), em " + d + " às " + t + ".";
    }

    @Tool(description = "Cancela um agendamento existente pelo id")
    public String cancel(@ToolParam(description = "Id do agendamento") Long id) {
        return repository.findById(id).map(appointment -> {
            if (appointment.getStatus() == AppointmentStatus.CANCELED) {
                return "O agendamento #" + id + " já estava cancelado.";
            }
            if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
                return "O agendamento #" + id + " já foi concluído e não pode ser cancelado.";
            }

            LocalDateTime when = parseDateTime(appointment.getAppointmentDate(), appointment.getAppointmentTime());
            String feeMessage = "";

            if (when != null) {
                long minutes = Duration.between(LocalDateTime.now(), when).toMinutes();
                if (minutes < 0) {
                    return "O horário do agendamento #" + id + " já passou, então não é possível cancelar.";
                }
                if (minutes < MIN_HOURS_TO_CANCEL * 60) {
                    Integer price = SERVICES.get(findService(appointment.getService()));
                    feeMessage = price != null
                            ? " Como faltam menos de " + MIN_HOURS_TO_CANCEL + " horas, pode ser cobrada taxa de 50% do valor do serviço (R$ "
                            + (price / 2) + ")."
                            : " Como faltam menos de " + MIN_HOURS_TO_CANCEL + " horas, pode ser cobrada taxa de 50% do valor do serviço.";
                }
            }

            appointment.setStatus(AppointmentStatus.CANCELED);
            repository.save(appointment);
            return "Agendamento #" + id + " cancelado." + feeMessage;
        }).orElse("Não encontrei nenhum agendamento com o id " + id + ".");
    }

    @Tool(description = "Reagenda um agendamento existente para uma nova data e hora")
    public String reschedule(
            @ToolParam(description = "Id do agendamento") Long id,
            @ToolParam(description = "Nova data no formato dd/MM/yyyy") String date,
            @ToolParam(description = "Nova hora no formato HH:mm") String time) {

        return repository.findById(id).map(appointment -> {
            if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
                return "O agendamento #" + id + " está como " + appointment.getStatus()
                        + " e não pode ser reagendado. Se necessário, crie um novo agendamento.";
            }

            String error = validateSlot(date, time, id);
            if (error != null) {
                return error;
            }

            String warning = "";
            LocalDateTime current = parseDateTime(appointment.getAppointmentDate(), appointment.getAppointmentTime());
            if (current != null
                    && Duration.between(LocalDateTime.now(), current).toMinutes() < MIN_HOURS_TO_CANCEL * 60) {
                warning = " Atenção: a remarcação sem custos exige no mínimo " + MIN_HOURS_TO_CANCEL
                        + " horas de antecedência, então pode haver cobrança.";
            }

            String d = LocalDate.parse(date, DATE_FORMAT).format(DATE_FORMAT);
            String t = LocalTime.parse(time, TIME_FORMAT).format(TIME_FORMAT);

            appointment.setAppointmentDate(d);
            appointment.setAppointmentTime(t);
            repository.save(appointment);
            return "Agendamento #" + id + " reagendado para " + d + " às " + t + "." + warning;
        }).orElse("Não encontrei nenhum agendamento com o id " + id + ".");
    }

    // ---------- validações e auxiliares ----------

    // devolve null se estiver tudo ok, ou a mensagem de erro
    private String validateSlot(String date, String time, Long ignoreId) {
        LocalDate parsedDate;
        LocalTime parsedTime;

        try {
            parsedDate = LocalDate.parse(date, DATE_FORMAT);
            parsedTime = LocalTime.parse(time, TIME_FORMAT);
        } catch (DateTimeParseException | NullPointerException e) {
            return "Data ou hora inválida. Use dd/MM/yyyy e HH:mm.";
        }

        LocalDateTime requested = LocalDateTime.of(parsedDate, parsedTime);

        if (requested.isBefore(LocalDateTime.now())) {
            return "Não é possível agendar em uma data/hora que já passou.";
        }
        if (requested.isBefore(LocalDateTime.now().plusHours(MIN_HOURS_TO_SCHEDULE))) {
            return "O agendamento precisa ser feito com pelo menos " + MIN_HOURS_TO_SCHEDULE + " horas de antecedência.";
        }
        if (parsedDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return "O salão não atende aos domingos. Atendemos de segunda a sábado.";
        }
        if (HOLIDAYS.contains(MonthDay.from(parsedDate))) {
            return "O salão não atende em feriados. Escolha outra data.";
        }
        if (parsedTime.isBefore(OPENING) || !parsedTime.isBefore(CLOSING)) {
            return "O salão funciona de segunda a sábado, das " + OPENING + " às " + CLOSING
                    + ". Escolha um horário dentro desse período.";
        }

        String d = parsedDate.format(DATE_FORMAT);
        String t = parsedTime.format(TIME_FORMAT);

        boolean busy = repository.findAll().stream().anyMatch(a ->
                a.getStatus() == AppointmentStatus.SCHEDULED
                        && (ignoreId == null || !ignoreId.equals(a.getId()))
                        && d.equals(a.getAppointmentDate())
                        && t.equals(a.getAppointmentTime()));

        if (busy) {
            return "Já existe um cliente agendado em " + d + " às " + t + ". Escolha outro horário.";
        }

        return null;
    }

    private String findService(String input) {
        if (input == null) {
            return null;
        }
        return SERVICES.keySet().stream()
                .filter(s -> s.equalsIgnoreCase(input.trim()))
                .findFirst()
                .orElse(null);
    }

    private LocalDateTime parseDateTime(String date, String time) {
        try {
            return LocalDateTime.of(LocalDate.parse(date, DATE_FORMAT), LocalTime.parse(time, TIME_FORMAT));
        } catch (DateTimeParseException | NullPointerException e) {
            return null;
        }
    }
}
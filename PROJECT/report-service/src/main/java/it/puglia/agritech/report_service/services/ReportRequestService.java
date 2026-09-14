package it.puglia.agritech.report_service.services;

import it.puglia.agritech.report_service.dtos.MarkProcessedRequest;
import it.puglia.agritech.report_service.dtos.MarkProcessedResponse;
import it.puglia.agritech.report_service.dtos.ReportQueryRequest;
import it.puglia.agritech.report_service.dtos.ReportQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Inoltra una richiesta di aggregazione al consumer-service via RabbitMQ e
 * ne attende la risposta sincrona.
 * <p>
 * Usa RabbitTemplate.sendAndReceive con un Message costruito a mano
 * (invece di convertSendAndReceive) per lo stesso motivo per cui il
 * consumer-service parsa i messaggi manualmente con ObjectMapper: evitare
 * di dover configurare un MessageConverter Spring AMQP coerente con
 * Jackson 3 (tools.jackson), la libreria JSON di default di Spring Boot 4,
 * quando Spring AMQP storicamente offre un converter pensato per Jackson 2
 * (com.fasterxml.jackson). Costruire/leggere i byte del messaggio a mano
 * mantiene il codice indipendente da quale converter sia disponibile.
 * <p>
 * sendAndReceive (non convertSendAndReceive) gestisce comunque in modo
 * trasparente il reply-to dinamico (direct reply-to) e la correlazione
 * della risposta: non c'è bisogno di impostare a mano reply-to o
 * correlationId sul messaggio in uscita.
 */
@Service
@RequiredArgsConstructor
public class ReportRequestService {

    private static final String REPORT_EXCHANGE = "agritech.reports";
    private static final String REPORT_ROUTING_KEY = "report.generate";
    private static final String MARK_PROCESSED_ROUTING_KEY = "report.mark-processed";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /**
     * @return la risposta, oppure null se non arriva entro il timeout
     * configurato su RabbitTemplate (vedi RabbitMqOAuth2Config).
     */
    public ReportQueryResponse requestAggregation(ReportQueryRequest request) {
        byte[] body = objectMapper.writeValueAsBytes(request);
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        Message requestMessage = new Message(body, props);

        Message replyMessage = rabbitTemplate.sendAndReceive(REPORT_EXCHANGE, REPORT_ROUTING_KEY, requestMessage);
        if (replyMessage == null) {
            return null;
        }
        return objectMapper.readValue(replyMessage.getBody(), ReportQueryResponse.class);
    }

    /**
     * Conferma al consumer-service che i dati usati per il report appena
     * generato e salvato possono essere marcati come processed. Va chiamato
     * solo dopo che il PDF è stato effettivamente salvato con successo (vedi
     * DailyReportService): mai prima, altrimenti dati mai davvero riportati
     * (perché la generazione fallisce dopo questa chiamata) sparirebbero
     * comunque dalle prossime elaborazioni.
     *
     * @return la risposta, oppure null se non arriva entro il timeout.
     */
    public MarkProcessedResponse markProcessed(String deviceId, String sensorType, Instant cutoff) {
        MarkProcessedRequest request = new MarkProcessedRequest(deviceId, sensorType, cutoff.toString());
        byte[] body = objectMapper.writeValueAsBytes(request);
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        Message requestMessage = new Message(body, props);

        Message replyMessage = rabbitTemplate.sendAndReceive(REPORT_EXCHANGE, MARK_PROCESSED_ROUTING_KEY, requestMessage);
        if (replyMessage == null) {
            return null;
        }
        return objectMapper.readValue(replyMessage.getBody(), MarkProcessedResponse.class);
    }
}

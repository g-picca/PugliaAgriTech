package it.puglia.agritech.consumer_service.listeners;

import com.rabbitmq.client.Channel;
import it.puglia.agritech.consumer_service.dtos.MarkProcessedRequest;
import it.puglia.agritech.consumer_service.dtos.MarkProcessedResponse;
import it.puglia.agritech.consumer_service.dtos.ReportQueryRequest;
import it.puglia.agritech.consumer_service.dtos.ReportQueryResponse;
import it.puglia.agritech.consumer_service.enums.AggregatableField;
import it.puglia.agritech.consumer_service.enums.AggregationType;
import it.puglia.agritech.consumer_service.repositories.SensorReadingAggregationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

/**
 * Riceve le richieste di aggregazione del report-service su 'report.requests'
 * e risponde sul reply-to dinamico (direct reply-to di AMQP: una
 * pseudo-coda 'amq.rabbitmq.reply-to.*' gestita internamente dal broker,
 * niente coda reale da dichiarare/eliminare per ogni richiesta).
 * <p>
 * Filosofia di gestione degli errori diversa da SensorDataListener: qui
 * il chiamante è un servizio interno fidato in attesa di una risposta
 * sincrona (RPC), non un dispositivo IoT non fidato. Una richiesta
 * malformata non viene quindi scartata in DLQ, ma produce una risposta di
 * errore esplicita — è un esito legittimo della conversazione, non
 * un'anomalia da mettere in quarantena. Il messaggio viene sempre
 * confermato (ack) dopo il tentativo di risposta.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportRequestListener {

    private final SensorReadingAggregationRepository aggregationRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "report.requests", containerFactory = "manualAckContainerFactory")
    public void onRequest(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String replyTo = message.getMessageProperties().getReplyTo();
        String correlationId = message.getMessageProperties().getCorrelationId();

        ReportQueryResponse response;
        try {
            ReportQueryRequest request = objectMapper.readValue(message.getBody(), ReportQueryRequest.class);
            response = handle(request);
        } catch (Exception ex) {
            log.warn("Richiesta report non valida o non deserializzabile", ex);
            response = ReportQueryResponse.error("Richiesta non valida: " + ex.getMessage());
        }

        if (replyTo != null) {
            reply(replyTo, correlationId, response, response.error() == null ? "OK" : "ERRORE: " + response.error());
        } else {
            log.warn("Richiesta report ricevuta senza reply-to: risposta scartata (esito={})",
                    response.error() == null ? "OK" : response.error());
        }
        channel.basicAck(deliveryTag, false);
    }

    /**
     * Comando di conferma inviato dal report-service dopo aver salvato con
     * successo il report giornaliero: marca come processed le letture usate.
     * Stessa filosofia di errore del metodo sopra (risposta esplicita, mai
     * DLQ, sempre ack) — vedi {@link MarkProcessedRequest}.
     */
    @RabbitListener(queues = "report.mark-processed", containerFactory = "manualAckContainerFactory")
    public void onMarkProcessed(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String replyTo = message.getMessageProperties().getReplyTo();
        String correlationId = message.getMessageProperties().getCorrelationId();

        MarkProcessedResponse response;
        try {
            MarkProcessedRequest request = objectMapper.readValue(message.getBody(), MarkProcessedRequest.class);
            response = handleMarkProcessed(request);
        } catch (Exception ex) {
            log.warn("Comando mark-processed non valido o non deserializzabile", ex);
            response = MarkProcessedResponse.error("Richiesta non valida: " + ex.getMessage());
        }

        if (replyTo != null) {
            reply(replyTo, correlationId, response, response.error() == null
                    ? "OK, " + response.rowsUpdated() + " righe aggiornate" : "ERRORE: " + response.error());
        }
        channel.basicAck(deliveryTag, false);
    }

    private MarkProcessedResponse handleMarkProcessed(MarkProcessedRequest request) {
        Instant cutoff;
        try {
            cutoff = Instant.parse(request.cutoff());
        } catch (Exception e) {
            return MarkProcessedResponse.error("Formato data non valido per 'cutoff', atteso ISO-8601");
        }
        long rowsUpdated = aggregationRepository.markProcessed(request.deviceId(), request.sensorType(), cutoff);
        log.info("Marcate processed {} letture (deviceId={}, sensorType={}, cutoff={})",
                rowsUpdated, request.deviceId(), request.sensorType(), cutoff);
        return MarkProcessedResponse.ok(rowsUpdated);
    }

    private ReportQueryResponse handle(ReportQueryRequest request) {
        AggregationType aggregation;
        try {
            aggregation = AggregationType.valueOf(request.aggregation().toUpperCase());
        } catch (Exception e) {
            return ReportQueryResponse.error("Tipo di aggregazione non valido: '" + request.aggregation()
                    + "' (ammessi: AVG, MIN, MAX, COUNT)");
        }

        AggregatableField field = null;
        if (aggregation != AggregationType.COUNT) {
            field = AggregatableField.byJsonKey(request.field()).orElse(null);
            if (field == null) {
                return ReportQueryResponse.error("Campo non aggregabile: '" + request.field() + "'");
            }
            if (request.sensorType() != null && !field.getSensorType().equals(request.sensorType())) {
                return ReportQueryResponse.error("Il campo '" + request.field()
                        + "' non appartiene al sensor_type '" + request.sensorType() + "'");
            }
        }

        if (request.deviceId() == null && request.sensorType() == null && field == null) {
            return ReportQueryResponse.error("Specificare almeno uno tra device_id e sensor_type");
        }

        boolean onlyUnprocessed = Boolean.TRUE.equals(request.onlyUnprocessed());
        Instant from = null;
        Instant to = null;
        Instant unprocessedCutoff = null;
        try {
            if (onlyUnprocessed) {
                unprocessedCutoff = Instant.parse(request.cutoff());
            } else {
                from = Instant.parse(request.from());
                to = Instant.parse(request.to());
                if (from.isAfter(to)) {
                    return ReportQueryResponse.error("'from' deve precedere 'to'");
                }
            }
        } catch (Exception e) {
            return ReportQueryResponse.error(onlyUnprocessed
                    ? "Formato data non valido per 'cutoff', atteso ISO-8601"
                    : "Formato data non valido, atteso ISO-8601 (es. 2026-01-01T00:00:00Z)");
        }

        String sensorTypeFilter = request.sensorType() != null
                ? request.sensorType()
                : (field != null ? field.getSensorType() : null);

        SensorReadingAggregationRepository.AggregationResult result = aggregationRepository.aggregate(
                aggregation, field, request.deviceId(), sensorTypeFilter, from, to, unprocessedCutoff);

        return new ReportQueryResponse(
                request.deviceId(), sensorTypeFilter, field != null ? field.getJsonKey() : null,
                aggregation.name(), request.from(), request.to(), result.value(), result.sampleCount(), null);
    }

    /**
     * Con il reply-to dinamico non c'è modo di sapere, da qui, se dall'altra
     * parte c'è ancora qualcuno in ascolto: se il chiamante ha già rinunciato
     * per timeout (RabbitTemplate.setReplyTimeout lato report-service), il
     * broker scarta silenziosamente il messaggio senza segnalare nulla a chi
     * pubblica — nessuna eccezione, nessun nack. Il log di esito qui sotto
     * (con lo stesso correlationId che compare nel WARN "Reply received
     * after timeout" lato report-service) è l'unico modo per distinguere,
     * a posteriori, "la richiesta è stata comunque completata, solo troppo
     * tardi" da "non è mai arrivata/è stata processata".
     */
    private void reply(String replyTo, String correlationId, Object response, String outcomeDescription) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(response);
            MessageProperties replyProps = new MessageProperties();
            replyProps.setCorrelationId(correlationId);
            replyProps.setContentType("application/json");
            Message replyMessage = new Message(body, replyProps);
            // Exchange di default (""), routing key = indirizzo di reply-to:
            // funziona sia per il reply-to dinamico (diretto) sia per un'eventuale
            // coda di risposta dichiarata esplicitamente.
            rabbitTemplate.send("", replyTo, replyMessage);
            log.info("Risposta inviata a '{}' (correlationId={}, esito={})", replyTo, correlationId, outcomeDescription);
        } catch (Exception e) {
            log.error("Impossibile inviare la risposta a '{}' (correlationId={})", replyTo, correlationId, e);
        }
    }
}

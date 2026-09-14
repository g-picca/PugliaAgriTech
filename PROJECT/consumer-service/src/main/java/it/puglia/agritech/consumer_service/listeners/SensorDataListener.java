package it.puglia.agritech.consumer_service.listeners;

import com.rabbitmq.client.Channel;
import it.puglia.agritech.consumer_service.entities.AuthorizedDevice;
import it.puglia.agritech.consumer_service.enums.IngestionEventType;
import it.puglia.agritech.consumer_service.entities.IngestionLog;
import it.puglia.agritech.consumer_service.entities.Sensor;
import it.puglia.agritech.consumer_service.entities.SensorReading;
import it.puglia.agritech.consumer_service.repositories.AuthorizedDeviceRepository;
import it.puglia.agritech.consumer_service.repositories.IngestionLogRepository;
import it.puglia.agritech.consumer_service.repositories.SensorReadingRepository;
import it.puglia.agritech.consumer_service.repositories.SensorRepository;
import it.puglia.agritech.consumer_service.utilities.HmacVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * Consuma i messaggi dei sensori dalla coda 'sensor.data' e li salva su
 * PostgreSQL, dopo aver verificato firma HMAC e autorizzazione del device.
 * <p>
 * Ack manuale: un messaggio viene confermato (ack) solo dopo essere stato
 * salvato con successo. Qualunque scarto (firma non valida, payload
 * malformato, device non autorizzato, errore imprevisto) è un nack senza
 * requeue, che instrada il messaggio verso 'sensor.dlq' grazie al
 * dead-letter-exchange già configurato sulla coda (definitions.json) —
 * evita sia la perdita silenziosa dei messaggi scartati sia un loop di
 * redelivery infinito sullo stesso messaggio non processabile.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensorDataListener {

    private final HmacVerifier hmacVerifier;
    private final SensorRepository sensorRepository;
    private final AuthorizedDeviceRepository authorizedDeviceRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final IngestionLogRepository ingestionLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Transazionale: tiene aperta la sessione Hibernate per l'intera durata
     * dell'elaborazione, necessaria perché autoProvision() legge
     * l'associazione lazy AuthorizedDevice.sensorType dopo la chiamata al
     * repository che l'ha caricata. Le eccezioni sono catturate all'interno
     * del metodo (mai propagate al proxy transazionale): la transazione
     * quindi si chiude comunque in commit, scrivendo l'evento ERROR su
     * ingestion_log anche quando il messaggio viene scartato.
     */
    @Transactional
    @RabbitListener(queues = "sensor.data", containerFactory = "manualAckContainerFactory")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String deviceId = null;

        try {
            HmacVerifier.Result verification = hmacVerifier.verify(message.getBody());
            if (!verification.valid()) {
                logEvent(null, IngestionEventType.REJECTED_SIGNATURE, "Firma HMAC assente o non valida");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            JsonNode root = objectMapper.readTree(verification.payloadWithoutSignature());
            deviceId = root.path("device_id").asString(null);
            String sensorTypeName = root.path("sensor_type").asString(null);
            JsonNode data = root.path("data");

            if (deviceId == null || sensorTypeName == null || !data.isObject()) {
                logEvent(deviceId, IngestionEventType.REJECTED_MALFORMED, "Campi obbligatori mancanti nel payload");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            Sensor sensor = resolveSensor(deviceId, sensorTypeName);
            if (sensor == null) {
                logEvent(deviceId, IngestionEventType.REJECTED_UNAUTHORIZED_DEVICE,
                        "Device sconosciuto e non presente nell'allowlist authorized_devices");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            // 'processed' impostato esplicitamente a false: non significa
            // "letto con successo dal listener" ma "non ancora confluito in un
            // report generato" — lo imposta a true solo il flusso di
            // generazione report (sezione 10 del riepilogo), mai l'ingestion.
            // Impostarlo esplicitamente (invece di lasciare il campo Java a
            // null sperando nel DEFAULT FALSE della colonna) è necessario:
            // Hibernate genera un INSERT che valorizza sempre tutte le
            // colonne mappate, quindi un campo Boolean non impostato
            // diventerebbe NULL scritto esplicitamente, non FALSE — e NULL
            // non soddisfa mai un filtro "processed = false" in SQL.
            SensorReading reading = new SensorReading();
            reading.setSensor(sensor);
            reading.setPayload(data.toString());
            reading.setProcessed(false);
            sensorReadingRepository.save(reading);

            logEvent(deviceId, IngestionEventType.ACCEPTED, null);
            channel.basicAck(deliveryTag, false);

        } catch (Exception ex) {
            log.error("Errore inatteso durante l'elaborazione di un messaggio sensore (device={})", deviceId, ex);
            logEvent(deviceId, IngestionEventType.ERROR, ex.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Cerca il sensore già registrato; se non esiste, lo auto-provisiona
     * SOLO se il device_id compare, attivo, nell'allowlist. Una firma HMAC
     * valida da sola non è sufficiente come autorizzazione, perché la
     * chiave è condivisa tra tutti i sensori.
     */
    private Sensor resolveSensor(String deviceId, String sensorTypeName) {
        return sensorRepository.findByDeviceId(deviceId)
                .orElseGet(() -> autoProvision(deviceId, sensorTypeName));
    }

    private Sensor autoProvision(String deviceId, String sensorTypeName) {
        AuthorizedDevice authorized = authorizedDeviceRepository.findByDeviceIdAndActiveTrue(deviceId)
                .orElse(null);
        if (authorized == null) {
            return null;
        }
        if (!authorized.getSensorType().getName().equals(sensorTypeName)) {
            log.warn("Device '{}' autorizzato per {} ma il messaggio dichiara sensor_type={}",
                    deviceId, authorized.getSensorType().getName(), sensorTypeName);
            return null;
        }

        Sensor sensor = new Sensor();
        sensor.setDeviceId(deviceId);
        sensor.setSensorType(authorized.getSensorType());
        sensor.setLocation(authorized.getLocation());
        sensor.setLatitude(authorized.getLatitude());
        sensor.setLongitude(authorized.getLongitude());
        sensor.setActive(true);

        try {
            return sensorRepository.save(sensor);
        } catch (DataIntegrityViolationException e) {
            // Un altro thread/consumer ha già auto-provisionato lo stesso device_id
            // nel frattempo (vincolo di unicità su device_id): rileggiamolo.
            return sensorRepository.findByDeviceId(deviceId).orElseThrow(() -> e);
        }
    }

    private void logEvent(String deviceId, IngestionEventType type, String detail) {
        try {
            IngestionLog entry = new IngestionLog();
            entry.setDeviceId(deviceId);
            entry.setEventType(type);
            entry.setDetail(detail);
            ingestionLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Impossibile scrivere su ingestion_log (device={}, evento={})", deviceId, type, e);
        }
    }
}

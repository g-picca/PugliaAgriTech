package it.puglia.agritech.consumer_service.listeners;

import com.rabbitmq.client.Channel;
import it.puglia.agritech.consumer_service.entities.AuthorizedDevice;
import it.puglia.agritech.consumer_service.entities.Sensor;
import it.puglia.agritech.consumer_service.entities.SensorType;
import it.puglia.agritech.consumer_service.enums.IngestionEventType;
import it.puglia.agritech.consumer_service.repositories.AuthorizedDeviceRepository;
import it.puglia.agritech.consumer_service.repositories.IngestionLogRepository;
import it.puglia.agritech.consumer_service.repositories.SensorReadingRepository;
import it.puglia.agritech.consumer_service.repositories.SensorRepository;
import it.puglia.agritech.consumer_service.utilities.HmacVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test del listener di ingestion (sezione 8 del riepilogo): verifica firma,
 * allowlist per l'auto-provisioning, ack/nack manuale. HmacVerifier e'
 * mockato (la sua correttezza e' testata a parte in HmacVerifierTest): qui
 * interessa solo cosa fa il listener in base all'esito della verifica, non
 * la verifica stessa.
 */
@ExtendWith(MockitoExtension.class)
class SensorDataListenerTest {

    @Mock
    private HmacVerifier hmacVerifier;
    @Mock
    private SensorRepository sensorRepository;
    @Mock
    private AuthorizedDeviceRepository authorizedDeviceRepository;
    @Mock
    private SensorReadingRepository sensorReadingRepository;
    @Mock
    private IngestionLogRepository ingestionLogRepository;
    @Mock
    private Channel channel;

    private SensorDataListener listener;

    @BeforeEach
    void setUp() {
        listener = new SensorDataListener(hmacVerifier, sensorRepository, authorizedDeviceRepository,
                sensorReadingRepository, ingestionLogRepository, new JsonMapper());
    }

    private static Message message(String body) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(1L);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    void knownSensor_validMessage_savesReadingAndAcks() throws Exception {
        String payload = "{\"device_id\":\"D1\",\"sensor_type\":\"TREE_TALKER\",\"data\":{\"x\":1}}";
        when(hmacVerifier.verify(any())).thenReturn(new HmacVerifier.Result(true, payload));

        Sensor sensor = new Sensor();
        sensor.setDeviceId("D1");
        when(sensorRepository.findByDeviceId("D1")).thenReturn(Optional.of(sensor));

        listener.onMessage(message(payload), channel);

        verify(sensorReadingRepository).save(argThat(r ->
                r.getSensor() == sensor && r.getPayload().equals("{\"x\":1}") && !r.getProcessed()));
        verify(ingestionLogRepository).save(argThat(
                log -> log.getEventType() == IngestionEventType.ACCEPTED));
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        // Il device e' gia' registrato: l'allowlist non va nemmeno consultata.
        verifyNoInteractions(authorizedDeviceRepository);
    }

    @Test
    void invalidSignature_nacksAndLogsRejectedSignature() throws Exception {
        // Result.invalid() e' package-private in HmacVerifier (package utilities):
        // da un altro package si usa direttamente il costruttore del record, pubblico.
        when(hmacVerifier.verify(any())).thenReturn(new HmacVerifier.Result(false, null));

        listener.onMessage(message("contenuto qualsiasi, non conta: la firma e' gia' invalida"), channel);

        verify(channel).basicNack(1L, false, false);
        verify(sensorReadingRepository, never()).save(any());
        verify(ingestionLogRepository).save(argThat(
                log -> log.getEventType() == IngestionEventType.REJECTED_SIGNATURE));
    }

    @Test
    void malformedPayload_missingDeviceId_nacksAndLogsRejectedMalformed() throws Exception {
        String payload = "{\"sensor_type\":\"TREE_TALKER\",\"data\":{\"x\":1}}"; // manca device_id
        when(hmacVerifier.verify(any())).thenReturn(new HmacVerifier.Result(true, payload));

        listener.onMessage(message(payload), channel);

        verify(channel).basicNack(1L, false, false);
        verify(ingestionLogRepository).save(argThat(
                log -> log.getEventType() == IngestionEventType.REJECTED_MALFORMED));
    }

    @Test
    void malformedPayload_dataFieldNotAnObject_nacksAndLogsRejectedMalformed() throws Exception {
        String payload = "{\"device_id\":\"D1\",\"sensor_type\":\"TREE_TALKER\",\"data\":\"non un oggetto\"}";
        when(hmacVerifier.verify(any())).thenReturn(new HmacVerifier.Result(true, payload));

        listener.onMessage(message(payload), channel);

        verify(channel).basicNack(1L, false, false);
        verify(sensorReadingRepository, never()).save(any());
    }

    @Test
    void unknownDeviceNotInAllowlist_nacksAndNeverCreatesSensor() throws Exception {
        String payload = "{\"device_id\":\"FAKE\",\"sensor_type\":\"TREE_TALKER\",\"data\":{\"x\":1}}";
        when(hmacVerifier.verify(any())).thenReturn(new HmacVerifier.Result(true, payload));
        when(sensorRepository.findByDeviceId("FAKE")).thenReturn(Optional.empty());
        when(authorizedDeviceRepository.findByDeviceIdAndActiveTrue("FAKE")).thenReturn(Optional.empty());

        listener.onMessage(message(payload), channel);

        verify(sensorRepository, never()).save(any());
        verify(channel).basicNack(1L, false, false);
        verify(ingestionLogRepository).save(argThat(
                log -> log.getEventType() == IngestionEventType.REJECTED_UNAUTHORIZED_DEVICE));
    }

    @Test
    void unknownButAuthorizedDevice_autoProvisionsFromAllowlistAndSaves() throws Exception {
        String payload = "{\"device_id\":\"NEW1\",\"sensor_type\":\"TREE_TALKER\",\"data\":{\"x\":1}}";
        when(hmacVerifier.verify(any())).thenReturn(new HmacVerifier.Result(true, payload));
        when(sensorRepository.findByDeviceId("NEW1")).thenReturn(Optional.empty());

        SensorType type = new SensorType();
        type.setName("TREE_TALKER");
        AuthorizedDevice authorized = new AuthorizedDevice();
        authorized.setDeviceId("NEW1");
        authorized.setSensorType(type);
        authorized.setLocation("Settore Test");
        when(authorizedDeviceRepository.findByDeviceIdAndActiveTrue("NEW1")).thenReturn(Optional.of(authorized));
        when(sensorRepository.save(any(Sensor.class))).thenAnswer(inv -> inv.getArgument(0));

        listener.onMessage(message(payload), channel);

        verify(sensorRepository).save(argThat(
                s -> "NEW1".equals(s.getDeviceId()) && Boolean.TRUE.equals(s.getActive())
                        && "Settore Test".equals(s.getLocation())));
        verify(sensorReadingRepository).save(any());
        verify(channel).basicAck(1L, false);
    }

    @Test
    void authorizedDeviceButDeclaredSensorTypeMismatch_treatedAsUnauthorized() throws Exception {
        // Autorizzato in allowlist come TREE_TALKER, ma il messaggio dichiara
        // AGROMETEO: la firma HMAC valida da sola non basta come autorizzazione
        // (chiave condivisa tra tutti i sensori, sezione 8.4 del riepilogo).
        String payload = "{\"device_id\":\"NEW1\",\"sensor_type\":\"AGROMETEO\",\"data\":{\"x\":1}}";
        when(hmacVerifier.verify(any())).thenReturn(new HmacVerifier.Result(true, payload));
        when(sensorRepository.findByDeviceId("NEW1")).thenReturn(Optional.empty());

        SensorType type = new SensorType();
        type.setName("TREE_TALKER");
        AuthorizedDevice authorized = new AuthorizedDevice();
        authorized.setDeviceId("NEW1");
        authorized.setSensorType(type);
        when(authorizedDeviceRepository.findByDeviceIdAndActiveTrue("NEW1")).thenReturn(Optional.of(authorized));

        listener.onMessage(message(payload), channel);

        verify(sensorRepository, never()).save(any());
        verify(channel).basicNack(1L, false, false);
    }

    @Test
    void unexpectedException_nacksAndLogsError() throws Exception {
        when(hmacVerifier.verify(any())).thenThrow(new RuntimeException("guasto simulato"));

        listener.onMessage(message("qualsiasi"), channel);

        verify(channel).basicNack(1L, false, false);
        verify(ingestionLogRepository).save(argThat(
                log -> log.getEventType() == IngestionEventType.ERROR));
    }
}

package it.puglia.agritech.consumer_service.listeners;

import com.rabbitmq.client.Channel;
import it.puglia.agritech.consumer_service.dtos.MarkProcessedResponse;
import it.puglia.agritech.consumer_service.dtos.ReportQueryResponse;
import it.puglia.agritech.consumer_service.enums.AggregationType;
import it.puglia.agritech.consumer_service.repositories.SensorReadingAggregationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test del listener request-reply (sezione 9 del riepilogo): validazione
 * della richiesta, whitelist di campo/aggregazione, e la modalita' "dati
 * non ancora processati" usata dalla generazione del report giornaliero
 * (sezione 10). SensorReadingAggregationRepository e' mockato: qui interessa
 * la logica di orchestrazione/validazione del listener, non la query stessa
 * (gia' testata implicitamente dai test end-to-end manuali sul database
 * reale, non riproducibili in un test unitario senza Postgres).
 */
@ExtendWith(MockitoExtension.class)
class ReportRequestListenerTest {

    private static final String REPLY_TO = "amq.rabbitmq.reply-to.test";
    private static final String CORRELATION_ID = "corr-1";

    @Mock
    private SensorReadingAggregationRepository aggregationRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private Channel channel;

    private final JsonMapper objectMapper = new JsonMapper();
    private ReportRequestListener listener;

    @BeforeEach
    void setUp() {
        listener = new ReportRequestListener(aggregationRepository, rabbitTemplate, objectMapper);
    }

    private static Message requestMessage(String json) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(1L);
        props.setReplyTo(REPLY_TO);
        props.setCorrelationId(CORRELATION_ID);
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    private ReportQueryResponse captureAggregationReply() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(REPLY_TO), captor.capture());
        return objectMapper.readValue(captor.getValue().getBody(), ReportQueryResponse.class);
    }

    private MarkProcessedResponse captureMarkProcessedReply() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(""), eq(REPLY_TO), captor.capture());
        return objectMapper.readValue(captor.getValue().getBody(), MarkProcessedResponse.class);
    }

    @Test
    void validRangeRequest_computesAggregationAndReplies() throws Exception {
        when(aggregationRepository.aggregate(eq(AggregationType.AVG), any(), eq("D1"), eq("TREE_TALKER"),
                any(Instant.class), any(Instant.class), isNull()))
                .thenReturn(new SensorReadingAggregationRepository.AggregationResult(12.5, 10));

        String requestJson = "{\"deviceId\":\"D1\",\"field\":\"sap_flow_cm_hr\",\"aggregation\":\"AVG\","
                + "\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}";

        listener.onRequest(requestMessage(requestJson), channel);

        ReportQueryResponse response = captureAggregationReply();
        assertThat(response.error()).isNull();
        assertThat(response.value()).isEqualTo(12.5);
        assertThat(response.sampleCount()).isEqualTo(10L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void invalidAggregationType_repliesWithErrorWithoutTouchingRepository() throws Exception {
        String requestJson = "{\"deviceId\":\"D1\",\"field\":\"sap_flow_cm_hr\",\"aggregation\":\"SUM\","
                + "\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}";

        listener.onRequest(requestMessage(requestJson), channel);

        ReportQueryResponse response = captureAggregationReply();
        assertThat(response.error()).contains("Tipo di aggregazione non valido");
        verifyNoInteractions(aggregationRepository);
        // Errore di validazione RPC: comunque un ack, mai una DLQ (sezione 9.5).
        verify(channel).basicAck(1L, false);
    }

    @Test
    void unknownField_repliesWithError() throws Exception {
        String requestJson = "{\"deviceId\":\"D1\",\"field\":\"campo_inventato\",\"aggregation\":\"AVG\","
                + "\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}";

        listener.onRequest(requestMessage(requestJson), channel);

        assertThat(captureAggregationReply().error()).contains("Campo non aggregabile");
    }

    @Test
    void fieldSensorTypeMismatch_repliesWithError() throws Exception {
        // sap_flow_cm_hr appartiene a TREE_TALKER, non ad AGROMETEO.
        String requestJson = "{\"deviceId\":\"D1\",\"sensorType\":\"AGROMETEO\",\"field\":\"sap_flow_cm_hr\","
                + "\"aggregation\":\"AVG\",\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}";

        listener.onRequest(requestMessage(requestJson), channel);

        assertThat(captureAggregationReply().error()).contains("non appartiene al sensor_type");
    }

    @Test
    void missingDeviceIdAndSensorType_repliesWithError() throws Exception {
        String requestJson = "{\"aggregation\":\"COUNT\","
                + "\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}";

        listener.onRequest(requestMessage(requestJson), channel);

        assertThat(captureAggregationReply().error()).contains("device_id e sensor_type");
    }

    @Test
    void invertedDateRange_repliesWithError() throws Exception {
        String requestJson = "{\"deviceId\":\"D1\",\"field\":\"sap_flow_cm_hr\",\"aggregation\":\"AVG\","
                + "\"from\":\"2026-01-02T00:00:00Z\",\"to\":\"2026-01-01T00:00:00Z\"}";

        listener.onRequest(requestMessage(requestJson), channel);

        assertThat(captureAggregationReply().error()).contains("'from' deve precedere 'to'");
    }

    @Test
    void malformedDate_repliesWithError() throws Exception {
        String requestJson = "{\"deviceId\":\"D1\",\"field\":\"sap_flow_cm_hr\",\"aggregation\":\"AVG\","
                + "\"from\":\"non-una-data\",\"to\":\"2026-01-01T00:00:00Z\"}";

        listener.onRequest(requestMessage(requestJson), channel);

        assertThat(captureAggregationReply().error()).contains("Formato data non valido");
    }

    @Test
    void unparsableJson_repliesWithGenericError() throws Exception {
        listener.onRequest(requestMessage("{questo non e' json valido"), channel);

        assertThat(captureAggregationReply().error()).contains("Richiesta non valida");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void onlyUnprocessedMode_passesCutoffInsteadOfDateRange() throws Exception {
        when(aggregationRepository.aggregate(eq(AggregationType.COUNT), isNull(), isNull(), eq("TREE_TALKER"),
                isNull(), isNull(), any(Instant.class)))
                .thenReturn(new SensorReadingAggregationRepository.AggregationResult(42.0, 42));

        String requestJson = "{\"sensorType\":\"TREE_TALKER\",\"aggregation\":\"COUNT\","
                + "\"onlyUnprocessed\":true,\"cutoff\":\"2026-01-01T00:00:00Z\"}";

        listener.onRequest(requestMessage(requestJson), channel);

        ReportQueryResponse response = captureAggregationReply();
        assertThat(response.error()).isNull();
        assertThat(response.sampleCount()).isEqualTo(42L);
    }

    @Test
    void markProcessedCommand_updatesAndRepliesWithCount() throws Exception {
        when(aggregationRepository.markProcessed(isNull(), eq("TREE_TALKER"), any(Instant.class)))
                .thenReturn(15L);

        String requestJson = "{\"sensorType\":\"TREE_TALKER\",\"cutoff\":\"2026-01-01T00:00:00Z\"}";

        listener.onMarkProcessed(requestMessage(requestJson), channel);

        MarkProcessedResponse response = captureMarkProcessedReply();
        assertThat(response.error()).isNull();
        assertThat(response.rowsUpdated()).isEqualTo(15L);
        verify(channel).basicAck(1L, false);
    }

    @Test
    void markProcessedCommand_invalidCutoff_repliesWithErrorWithoutTouchingRepository() throws Exception {
        String requestJson = "{\"sensorType\":\"TREE_TALKER\",\"cutoff\":\"non-una-data\"}";

        listener.onMarkProcessed(requestMessage(requestJson), channel);

        assertThat(captureMarkProcessedReply().error()).contains("Formato data non valido");
        verifyNoInteractions(aggregationRepository);
    }
}

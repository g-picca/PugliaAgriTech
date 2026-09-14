package it.puglia.agritech.report_service.services;

import it.puglia.agritech.report_service.dtos.MarkProcessedResponse;
import it.puglia.agritech.report_service.dtos.ReportQueryRequest;
import it.puglia.agritech.report_service.dtos.ReportQueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestratore del report giornaliero: interroga il consumer-service per i
 * dati non ancora processati, costruisce il PDF, lo salva, e solo a quel
 * punto conferma l'elaborazione (sezione 10 del riepilogo).
 * <p>
 * Limite noto e consapevole: il report aggrega per tipo di sensore, non per
 * singolo device. Il report-service non ha accesso al database e non
 * conosce l'elenco dei device esistenti — servirebbe una nuova richiesta
 * dedicata ("elenco sensori attivi per tipo") per un dettaglio per-device,
 * non ancora implementata.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DailyReportService {

    /**
     * Campi aggregabili per tipo di sensore, speculari alla whitelist
     * AggregatableField del consumer-service (duplicata qui per lo stesso
     * motivo per cui i DTO sono duplicati: microservizi indipendenti).
     */
    private static final Map<String, List<String>> FIELDS_BY_SENSOR_TYPE = new LinkedHashMap<>();

    static {
        FIELDS_BY_SENSOR_TYPE.put("TREE_TALKER", List.of("sap_flow_cm_hr", "stem_humidity_percent", "leaf_color_index"));
        FIELDS_BY_SENSOR_TYPE.put("AGROMETEO",
                List.of("soil_moisture_percent", "air_temperature_c", "air_humidity_percent", "leaf_wetness_hours"));
    }

    private final ReportRequestService reportRequestService;
    private final PdfReportBuilder pdfReportBuilder;
    private final ReportStorageService reportStorageService;
    private final ReentrantLock generationLock = new ReentrantLock();

    public record GenerationResult(boolean success, boolean alreadyRunning, String error, Path path) {
        static GenerationResult ok(Path path) {
            return new GenerationResult(true, false, null, path);
        }

        static GenerationResult error(String message) {
            return new GenerationResult(false, false, message, null);
        }

        static GenerationResult busy() {
            return new GenerationResult(false, true, null, null);
        }
    }

    public GenerationResult generateDailyReport() {
        if (!generationLock.tryLock()) {
            log.warn("Generazione report già in corso: richiesta ignorata (una sola istanza per volta)");
            return GenerationResult.busy();
        }
        try {
            // Un solo cutoff per l'intera generazione: tutte le richieste di
            // aggregazione e la successiva conferma devono riferirsi allo
            // stesso istante, altrimenti letture arrivate a metà generazione
            // potrebbero comparire in alcune metriche e non in altre.
            Instant cutoff = Instant.now();
            log.info("Avvio generazione report giornaliero (cutoff={})", cutoff);

            List<PdfReportBuilder.SensorTypeSection> sections = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : FIELDS_BY_SENSOR_TYPE.entrySet()) {
                sections.add(buildSection(entry.getKey(), entry.getValue(), cutoff));
            }

            Instant generatedAt = Instant.now();
            byte[] pdfBytes;
            try {
                pdfBytes = pdfReportBuilder.build(generatedAt, cutoff, sections);
            } catch (IOException e) {
                log.error("Errore nella generazione del PDF", e);
                return GenerationResult.error("Errore nella generazione del PDF: " + e.getMessage());
            }

            Path savedPath;
            try {
                savedPath = reportStorageService.save(generatedAt, pdfBytes);
            } catch (IOException e) {
                log.error("Errore nel salvataggio del PDF su disco", e);
                return GenerationResult.error("Errore nel salvataggio del PDF: " + e.getMessage());
            }

            confirmProcessed(cutoff);

            log.info("Report giornaliero generato e salvato: {}", savedPath);
            return GenerationResult.ok(savedPath);
        } finally {
            generationLock.unlock();
        }
    }

    private PdfReportBuilder.SensorTypeSection buildSection(String sensorType, List<String> fields, Instant cutoff) {
        List<PdfReportBuilder.MetricRow> rows = new ArrayList<>();
        for (String field : fields) {
            ReportQueryResponse avg = queryUnprocessed(sensorType, field, "AVG", cutoff);
            if (avg == null) {
                log.error("Timeout nella richiesta AVG per {}/{}: campo escluso dal report", sensorType, field);
                continue;
            }
            if (avg.error() != null) {
                log.error("Errore nella richiesta AVG per {}/{}: {} — campo escluso dal report",
                        sensorType, field, avg.error());
                continue;
            }

            long sampleCount = avg.sampleCount() != null ? avg.sampleCount() : 0;
            Double min = sampleCount > 0 ? valueOrNull(queryUnprocessed(sensorType, field, "MIN", cutoff)) : null;
            Double max = sampleCount > 0 ? valueOrNull(queryUnprocessed(sensorType, field, "MAX", cutoff)) : null;

            rows.add(new PdfReportBuilder.MetricRow(field, avg.value(), min, max, sampleCount));
        }
        return new PdfReportBuilder.SensorTypeSection(sensorType, rows);
    }

    private Double valueOrNull(ReportQueryResponse response) {
        return (response != null && response.error() == null) ? response.value() : null;
    }

    private ReportQueryResponse queryUnprocessed(String sensorType, String field, String aggregation, Instant cutoff) {
        ReportQueryRequest request = new ReportQueryRequest(
                null, sensorType, field, aggregation, null, null, true, cutoff.toString());
        return reportRequestService.requestAggregation(request);
    }

    private void confirmProcessed(Instant cutoff) {
        for (String sensorType : FIELDS_BY_SENSOR_TYPE.keySet()) {
            MarkProcessedResponse response = reportRequestService.markProcessed(null, sensorType, cutoff);
            if (response == null) {
                log.error("Timeout nella conferma mark-processed per sensorType={} — verrà ritentato al prossimo run",
                        sensorType);
            } else if (response.error() != null) {
                log.error("Errore mark-processed per sensorType={}: {}", sensorType, response.error());
            } else {
                log.info("Confermate processed {} letture per sensorType={}", response.rowsUpdated(), sensorType);
            }
        }
    }
}

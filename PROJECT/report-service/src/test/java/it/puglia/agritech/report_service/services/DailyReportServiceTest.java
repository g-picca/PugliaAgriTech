package it.puglia.agritech.report_service.services;

import it.puglia.agritech.report_service.dtos.MarkProcessedResponse;
import it.puglia.agritech.report_service.dtos.ReportQueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test dell'orchestratore del report giornaliero (sezione 10.2 del
 * riepilogo): la sequenza corretta e' recupero dati -> generazione PDF ->
 * salvataggio -> conferma "processed", in quest'ordine, e la conferma deve
 * avvenire SOLO dopo un salvataggio riuscito (sezione 10.2, decisione delle
 * due fasi). ReportRequestService/PdfReportBuilder/ReportStorageService
 * sono mockati: qui interessa l'orchestrazione, non le singole chiamate
 * RabbitMQ o il rendering PDF (testati a parte).
 */
@ExtendWith(MockitoExtension.class)
class DailyReportServiceTest {

    @Mock
    private ReportRequestService reportRequestService;
    @Mock
    private PdfReportBuilder pdfReportBuilder;
    @Mock
    private ReportStorageService reportStorageService;

    private DailyReportService service;

    @BeforeEach
    void setUp() {
        service = new DailyReportService(reportRequestService, pdfReportBuilder, reportStorageService);
    }

    private static ReportQueryResponse okAggregation() {
        return new ReportQueryResponse(null, null, null, "AVG", null, null, 10.0, 5L, null);
    }

    @Test
    void successfulGeneration_savesPdfAndConfirmsProcessed() throws Exception {
        when(reportRequestService.requestAggregation(any())).thenReturn(okAggregation());
        when(pdfReportBuilder.build(any(), any(), any())).thenReturn(new byte[]{1, 2, 3});
        Path savedPath = Path.of("reports", "report-test.pdf");
        when(reportStorageService.save(any(), any())).thenReturn(savedPath);
        when(reportRequestService.markProcessed(any(), any(), any()))
                .thenReturn(new MarkProcessedResponse(5L, null));

        DailyReportService.GenerationResult result = service.generateDailyReport();

        assertThat(result.success()).isTrue();
        assertThat(result.alreadyRunning()).isFalse();
        assertThat(result.path()).isEqualTo(savedPath);
        verify(reportStorageService).save(any(), any());
        // Una conferma "mark processed" per ciascun tipo di sensore gestito
        // (TREE_TALKER, AGROMETEO): vedi FIELDS_BY_SENSOR_TYPE.
        verify(reportRequestService, org.mockito.Mockito.times(2)).markProcessed(any(), any(), any());
    }

    @Test
    void pdfGenerationFails_doesNotSaveOrConfirmProcessed() throws Exception {
        when(reportRequestService.requestAggregation(any())).thenReturn(okAggregation());
        when(pdfReportBuilder.build(any(), any(), any())).thenThrow(new IOException("errore di rendering"));

        DailyReportService.GenerationResult result = service.generateDailyReport();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("errore di rendering");
        verify(reportStorageService, never()).save(any(), any());
        verify(reportRequestService, never()).markProcessed(any(), any(), any());
    }

    @Test
    void saveFails_doesNotConfirmProcessed() throws Exception {
        when(reportRequestService.requestAggregation(any())).thenReturn(okAggregation());
        when(pdfReportBuilder.build(any(), any(), any())).thenReturn(new byte[]{1});
        when(reportStorageService.save(any(), any())).thenThrow(new IOException("disco pieno"));

        DailyReportService.GenerationResult result = service.generateDailyReport();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("disco pieno");
        // Il fix strutturale a difesa dei dati (sezione 10.2): un fallimento
        // nel salvataggio non deve mai portare a una conferma "processed".
        verify(reportRequestService, never()).markProcessed(any(), any(), any());
    }

    @Test
    void aggregationRequestTimesOut_fieldIsSkippedButGenerationStillSucceeds() throws Exception {
        // Timeout simulato (sezione 9.6): nessuna risposta entro il termine.
        when(reportRequestService.requestAggregation(any())).thenReturn(null);
        when(pdfReportBuilder.build(any(), any(), any())).thenReturn(new byte[]{1});
        when(reportStorageService.save(any(), any())).thenReturn(Path.of("reports", "report-test.pdf"));
        when(reportRequestService.markProcessed(any(), any(), any()))
                .thenReturn(new MarkProcessedResponse(0L, null));

        DailyReportService.GenerationResult result = service.generateDailyReport();

        // Un timeout su una singola metrica non deve far fallire l'intera
        // generazione: il campo viene semplicemente escluso dal report.
        assertThat(result.success()).isTrue();
    }

    @Test
    void aggregationRequestReturnsError_fieldIsSkippedButGenerationStillSucceeds() throws Exception {
        ReportQueryResponse errorResponse =
                new ReportQueryResponse(null, null, null, null, null, null, null, null, "errore simulato");
        when(reportRequestService.requestAggregation(any())).thenReturn(errorResponse);
        when(pdfReportBuilder.build(any(), any(), any())).thenReturn(new byte[]{1});
        when(reportStorageService.save(any(), any())).thenReturn(Path.of("reports", "report-test.pdf"));
        when(reportRequestService.markProcessed(any(), any(), any()))
                .thenReturn(new MarkProcessedResponse(0L, null));

        DailyReportService.GenerationResult result = service.generateDailyReport();

        assertThat(result.success()).isTrue();
    }

    @Test
    void markProcessedTimesOut_generationStillReportsSuccess() throws Exception {
        // La conferma e' un passo separato e successivo (sezione 9.6): se non
        // arriva risposta, il report e' comunque stato generato e salvato con
        // successo; il prossimo run ripeschera' semplicemente le stesse letture.
        when(reportRequestService.requestAggregation(any())).thenReturn(okAggregation());
        when(pdfReportBuilder.build(any(), any(), any())).thenReturn(new byte[]{1});
        when(reportStorageService.save(any(), any())).thenReturn(Path.of("reports", "report-test.pdf"));
        when(reportRequestService.markProcessed(any(), any(), any())).thenReturn(null);

        DailyReportService.GenerationResult result = service.generateDailyReport();

        assertThat(result.success()).isTrue();
    }

    @Test
    void generateDailyReport_usesASingleCutoffForAllRequestsInOneRun() throws Exception {
        when(reportRequestService.requestAggregation(any())).thenReturn(okAggregation());
        when(pdfReportBuilder.build(any(), any(), any())).thenReturn(new byte[]{1});
        when(reportStorageService.save(any(), any())).thenReturn(Path.of("reports", "report-test.pdf"));
        when(reportRequestService.markProcessed(any(), any(), any()))
                .thenReturn(new MarkProcessedResponse(0L, null));

        org.mockito.ArgumentCaptor<Instant> cutoffCaptor = org.mockito.ArgumentCaptor.forClass(Instant.class);

        service.generateDailyReport();

        verify(reportRequestService, org.mockito.Mockito.times(2))
                .markProcessed(any(), any(), cutoffCaptor.capture());
        assertThat(cutoffCaptor.getAllValues()).containsOnly(cutoffCaptor.getAllValues().get(0));
    }
}

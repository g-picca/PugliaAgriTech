package it.puglia.agritech.report_service.services;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test del rendering del report PDF (sezione 10.2 del riepilogo): traduzione
 * dei nomi tecnici dei campi in etichette italiane, gestione delle sezioni
 * senza dati, formattazione dei valori. Verifica leggendo il testo estratto
 * dal PDF prodotto, non solo che la generazione non sollevi eccezioni.
 */
class PdfReportBuilderTest {

    private final PdfReportBuilder builder = new PdfReportBuilder();

    private static String extractText(byte[] pdfBytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void build_knownField_translatesLabelAndHidesTechnicalName() throws Exception {
        var rows = List.of(new PdfReportBuilder.MetricRow("air_temperature_c", 25.5, 18.2, 30.1, 42));
        var sections = List.of(new PdfReportBuilder.SensorTypeSection("AGROMETEO", rows));

        byte[] pdf = builder.build(Instant.parse("2026-01-01T10:00:00Z"),
                Instant.parse("2026-01-01T09:00:00Z"), sections);
        String text = extractText(pdf);

        assertThat(text).contains("Dato Rilevato");
        assertThat(text).contains("Temperatura Aria(°C)");
        assertThat(text).doesNotContain("air_temperature_c");
        assertThat(text).contains("42");
    }

    @Test
    void build_unknownField_fallsBackToTechnicalName() throws Exception {
        var rows = List.of(new PdfReportBuilder.MetricRow("campo_sconosciuto", 1.0, 1.0, 1.0, 1));
        var sections = List.of(new PdfReportBuilder.SensorTypeSection("ALTRO", rows));

        byte[] pdf = builder.build(Instant.now(), Instant.now(), sections);

        assertThat(extractText(pdf)).contains("campo_sconosciuto");
    }

    @Test
    void build_emptySection_showsPlaceholderMessage() throws Exception {
        var sections = List.of(new PdfReportBuilder.SensorTypeSection("TREE_TALKER", List.of()));

        byte[] pdf = builder.build(Instant.now(), Instant.now(), sections);

        assertThat(extractText(pdf)).contains("Nessun dato nuovo da riportare");
    }

    @Test
    void build_allRowsWithZeroSamples_showsPlaceholderMessage() throws Exception {
        var rows = List.of(new PdfReportBuilder.MetricRow("leaf_color_index", null, null, null, 0));
        var sections = List.of(new PdfReportBuilder.SensorTypeSection("TREE_TALKER", rows));

        byte[] pdf = builder.build(Instant.now(), Instant.now(), sections);

        assertThat(extractText(pdf)).contains("Nessun dato nuovo da riportare");
    }

    @Test
    void build_multipleSections_allAppearInOutput() throws Exception {
        var treeTalkerRows = List.of(new PdfReportBuilder.MetricRow("sap_flow_cm_hr", 12.3, 8.0, 16.0, 10));
        var agrometeoRows = List.of(new PdfReportBuilder.MetricRow("soil_moisture_percent", 25.0, 20.0, 30.0, 5));
        var sections = List.of(
                new PdfReportBuilder.SensorTypeSection("TREE_TALKER", treeTalkerRows),
                new PdfReportBuilder.SensorTypeSection("AGROMETEO", agrometeoRows)
        );

        String text = extractText(builder.build(Instant.now(), Instant.now(), sections));

        assertThat(text).contains("TREE_TALKER");
        assertThat(text).contains("AGROMETEO");
        assertThat(text).contains("Flusso Linfatico(cm/h)");
        assertThat(text).contains("Umidità Suolo(%)");
    }
}

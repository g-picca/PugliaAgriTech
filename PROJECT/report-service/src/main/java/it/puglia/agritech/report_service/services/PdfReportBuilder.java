package it.puglia.agritech.report_service.services;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Costruisce il PDF del report giornaliero con PDFBox, a basso livello
 * (PDPageContentStream, posizionamento manuale del testo): PDFBox non offre
 * un componente tabella pronto come le librerie di livello più alto
 * (es. OpenPDF), ma evita ogni dubbio di licenza copyleft (Apache 2.0).
 * Per un report a poche righe come questo, il costo della verbosità in più
 * è accettabile.
 */
@Component
public class PdfReportBuilder {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Etichette in italiano per i campi tecnici (nomi delle chiavi JSON usate
     * internamente, sezione 9.2 del riepilogo). Solo di presentazione: il
     * nome tecnico resta quello scambiato con il consumer-service, tradotto
     * qui solo al momento di scrivere il PDF.
     */
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("sap_flow_cm_hr", "Flusso Linfatico(cm/h)"),
            Map.entry("stem_humidity_percent", "Umidità Fusto(%)"),
            Map.entry("leaf_color_index", "Indice Colore Fogliare"),
            Map.entry("soil_moisture_percent", "Umidità Suolo(%)"),
            Map.entry("air_temperature_c", "Temperatura Aria(°C)"),
            Map.entry("air_humidity_percent", "Umidità Aria(%)"),
            Map.entry("leaf_wetness_hours", "Ore Bagnatura Fogliare(h)")
    );

    public record MetricRow(String field, Double avg, Double min, Double max, long sampleCount) {
    }

    public record SensorTypeSection(String sensorType, List<MetricRow> rows) {
    }

    public byte[] build(Instant generatedAt, Instant cutoff, List<SensorTypeSection> sections) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDType1Font titleFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font headerFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            float margin = 50f;
            float y = page.getMediaBox().getHeight() - margin;

            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                y = writeLine(cs, titleFont, 16, margin, y,
                        "Report giornaliero AgriTech - monitoraggio uliveti");
                y -= 8;
                y = writeLine(cs, bodyFont, 10, margin, y, "Generato il: " + TIMESTAMP_FORMAT.format(generatedAt));
                y = writeLine(cs, bodyFont, 10, margin, y,
                        "Dati non ancora inclusi in un report precedente, ricevuti fino al: "
                                + TIMESTAMP_FORMAT.format(cutoff));
                y -= 14;

                for (SensorTypeSection section : sections) {
                    y = writeLine(cs, headerFont, 13, margin, y, section.sensorType());
                    y -= 4;

                    boolean noData = section.rows().isEmpty()
                            || section.rows().stream().allMatch(r -> r.sampleCount() == 0);
                    if (noData) {
                        y = writeLine(cs, bodyFont, 10, margin, y, "Nessun dato nuovo da riportare.");
                        y -= 14;
                        continue;
                    }

                    float[] colX = {margin, margin + 220, margin + 300, margin + 370, margin + 440};
                    y = writeRow(cs, headerFont, 10, y, colX,
                            "Dato Rilevato", "Media", "Min", "Max", "Campioni");
                    y -= 4;

                    for (MetricRow row : section.rows()) {
                        String avgText = row.sampleCount() > 0 ? format(row.avg()) : "-";
                        String minText = row.sampleCount() > 0 ? format(row.min()) : "-";
                        String maxText = row.sampleCount() > 0 ? format(row.max()) : "-";
                        y = writeRow(cs, bodyFont, 10, y, colX,
                                labelFor(row.field()), avgText, minText, maxText, String.valueOf(row.sampleCount()));
                    }
                    y -= 14;
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static String format(Double value) {
        return value == null ? "-" : String.format(Locale.ITALIAN, "%.2f", value);
    }

    private static String labelFor(String field) {
        return FIELD_LABELS.getOrDefault(field, field);
    }

    private float writeLine(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
        return y - (size + 6);
    }

    private float writeRow(PDPageContentStream cs, PDType1Font font, float size, float y, float[] colX, String... values)
            throws IOException {
        for (int i = 0; i < values.length; i++) {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(colX[i], y);
            cs.showText(values[i]);
            cs.endText();
        }
        return y - (size + 6);
    }
}

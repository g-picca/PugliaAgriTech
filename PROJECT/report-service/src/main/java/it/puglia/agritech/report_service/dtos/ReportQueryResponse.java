package it.puglia.agritech.report_service.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReportQueryResponse(
        String deviceId,
        String sensorType,
        String field,
        String aggregation,
        String from,
        String to,
        @Schema(description = "Valore calcolato. Assente se 'error' è valorizzato.")
        Double value,
        @Schema(description = "Numero di letture su cui è stato calcolato il valore.")
        Long sampleCount,
        @Schema(description = "Messaggio d'errore di validazione. Assente in caso di successo.")
        String error
) {
}

package it.puglia.agritech.report_service.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stesso contratto di messaggio del consumer-service (dtos.ReportQueryRequest
 * lì). Duplicato deliberatamente invece di condiviso tramite una libreria
 * comune: coerente con la scelta architetturale già presa nel progetto di
 * microservizi Spring Boot indipendenti, non multi-modulo Maven (vedi
 * sezione 3 del riepilogo). Il contratto è comunque implicito e va tenuto
 * sincronizzato manualmente tra i due servizi.
 */
public record ReportQueryRequest(
        @Schema(description = "ID del sensore. Obbligatorio insieme a 'sensorType' se non si usa 'onlyUnprocessed'.", example = "OLIVO_SEC_001")
        String deviceId,
        @Schema(description = "Tipo di sensore.", example = "TREE_TALKER", allowableValues = {"TREE_TALKER", "AGROMETEO"})
        String sensorType,
        @Schema(description = "Campo da aggregare (deve appartenere al sensorType indicato).", example = "sap_flow_cm_hr")
        String field,
        @Schema(description = "Funzione di aggregazione.", example = "AVG", allowableValues = {"AVG", "MIN", "MAX", "COUNT"})
        String aggregation,
        @Schema(description = "Inizio periodo (ISO-8601). Alternativo a 'onlyUnprocessed'.", example = "2026-09-01T00:00:00Z")
        String from,
        @Schema(description = "Fine periodo (ISO-8601). Alternativo a 'onlyUnprocessed'.", example = "2026-09-14T00:00:00Z")
        String to,
        @Schema(description = "Se true, ignora 'from'/'to' e aggrega solo le letture non ancora processate, più vecchie di 'cutoff'.")
        Boolean onlyUnprocessed,
        @Schema(description = "Usato solo con 'onlyUnprocessed=true': limite superiore (ISO-8601) delle letture da considerare.", example = "2026-09-14T00:00:00Z")
        String cutoff
) {
}

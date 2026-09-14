package it.puglia.agritech.report_service.dtos;

public record MarkProcessedRequest(
        String deviceId,
        String sensorType,
        String cutoff
) {
}

package it.puglia.agritech.report_service.dtos;

public record MarkProcessedResponse(
        Long rowsUpdated,
        String error
) {
}

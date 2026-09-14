package it.puglia.agritech.consumer_service.dtos;

public record MarkProcessedResponse(
        Long rowsUpdated,
        String error
) {
    public static MarkProcessedResponse error(String message) {
        return new MarkProcessedResponse(null, message);
    }

    public static MarkProcessedResponse ok(long rowsUpdated) {
        return new MarkProcessedResponse(rowsUpdated, null);
    }
}

package it.puglia.agritech.consumer_service.dtos;

/**
 * Risposta all'aggregazione. In caso di errore tutti i campi salvo 'error'
 * sono null: un'unica forma di messaggio, niente eccezioni sul canale AMQP
 * (un errore di validazione della richiesta è una risposta legittima di
 * un RPC, non un motivo per scartare il messaggio o interrompere il flusso).
 */
public record ReportQueryResponse(
        String deviceId,
        String sensorType,
        String field,
        String aggregation,
        String from,
        String to,
        Double value,
        Long sampleCount,
        String error
) {
    public static ReportQueryResponse error(String message) {
        return new ReportQueryResponse(null, null, null, null, null, null, null, null, message);
    }
}

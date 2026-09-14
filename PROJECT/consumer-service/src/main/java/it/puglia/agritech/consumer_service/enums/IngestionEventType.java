package it.puglia.agritech.consumer_service.enums;

/**
 * Esito dell'elaborazione di un singolo messaggio ricevuto dal listener,
 * registrato in 'ingestion_log' per monitoraggio e audit di sicurezza.
 */
public enum IngestionEventType {
    ACCEPTED,
    REJECTED_SIGNATURE,
    REJECTED_UNAUTHORIZED_DEVICE,
    REJECTED_MALFORMED,
    ERROR
}

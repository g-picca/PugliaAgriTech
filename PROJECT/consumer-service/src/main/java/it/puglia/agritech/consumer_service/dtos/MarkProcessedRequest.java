package it.puglia.agritech.consumer_service.dtos;

/**
 * Comando inviato dal report-service dopo aver generato e salvato con
 * successo il report giornaliero: marca come processed tutte le letture
 * non ancora processate, ricevute prima di 'cutoff', che soddisfano il
 * filtro (stesso deviceId/sensorType e stesso cutoff usati per le
 * richieste di aggregazione che hanno prodotto il contenuto del report).
 * <p>
 * Inviato deliberatamente DOPO il salvataggio del PDF, mai prima: se la
 * generazione fallisse a metà, le letture restano non processate e
 * rientrano nel prossimo run, invece di sparire dalla reportistica senza
 * essere mai state davvero riportate.
 */
public record MarkProcessedRequest(
        String deviceId,
        String sensorType,
        String cutoff
) {
}

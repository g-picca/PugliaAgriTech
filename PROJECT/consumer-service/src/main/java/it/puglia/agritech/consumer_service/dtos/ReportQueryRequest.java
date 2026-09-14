package it.puglia.agritech.consumer_service.dtos;

/**
 * Richiesta di aggregazione ricevuta dal report-service su 'report.requests'.
 * deviceId e sensorType sono entrambi opzionali ma non entrambi assenti:
 * se deviceId è presente si aggrega su quel solo sensore, altrimenti su
 * tutti i sensori del sensorType indicato. field è ignorato per COUNT.
 * <p>
 * Due modalità di filtro temporale, alternative:
 * - intervallo esplicito: valorizzare from/to (uso interattivo, es. una
 *   dashboard che chiede "media di questo mese");
 * - dati non ancora processati: onlyUnprocessed=true e cutoff valorizzato,
 *   from/to ignorati. Filtra su processed=false AND received_at < cutoff.
 *   Usata dalla generazione del report giornaliero (sezione 10 del
 *   riepilogo): permette di aggregare esattamente i dati non ancora
 *   confluiti in un report precedente, senza doverne conoscere l'intervallo
 *   di date esatto.
 */
public record ReportQueryRequest(
        String deviceId,
        String sensorType,
        String field,
        String aggregation,
        String from,
        String to,
        Boolean onlyUnprocessed,
        String cutoff
) {
}

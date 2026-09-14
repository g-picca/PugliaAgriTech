package it.puglia.agritech.consumer_service.enums;

/**
 * Tipi di aggregazione supportati dal servizio di reportistica.
 * Il nome dell'enum coincide deliberatamente con il nome della funzione SQL
 * corrispondente (AVG, MIN, MAX): viene usato per costruire la query nativa
 * in SensorReadingAggregationRepository. Poiché il valore arriva da una
 * richiesta esterna (report-service), non è mai concatenato direttamente:
 * si passa sempre da AggregationType.valueOf(...), che accetta solo questi
 * quattro valori e solleva un'eccezione per qualunque altra stringa — è
 * di fatto una whitelist, non un'interpolazione di input arbitrario.
 */
public enum AggregationType {
    AVG,
    MIN,
    MAX,
    COUNT
}

package it.puglia.agritech.consumer_service.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.Optional;

/**
 * Whitelist dei campi numerici del JSON 'data' su cui è consentito
 * calcolare un'aggregazione (AVG/MIN/MAX), uno per tipo di sensore.
 * <p>
 * Il nome del campo arriva da una richiesta esterna (report-service) e
 * finisce dentro il testo di una query nativa (per estrarre il valore da
 * 'payload->>'campo''): non viene MAI usato il valore ricevuto così com'è.
 * Si cerca prima una corrispondenza esatta qui (byJsonKey); solo se esiste
 * si usa il valore letterale dell'enum (jsonKey), scritto da noi in fase di
 * sviluppo, mai il testo arrivato dalla rete. Qualunque altro nome viene
 * respinto prima ancora di toccare il database.
 */
@Getter
public enum AggregatableField {
    SAP_FLOW_CM_HR("TREE_TALKER", "sap_flow_cm_hr"),
    STEM_HUMIDITY_PERCENT("TREE_TALKER", "stem_humidity_percent"),
    LEAF_COLOR_INDEX("TREE_TALKER", "leaf_color_index"),
    SOIL_MOISTURE_PERCENT("AGROMETEO", "soil_moisture_percent"),
    AIR_TEMPERATURE_C("AGROMETEO", "air_temperature_c"),
    AIR_HUMIDITY_PERCENT("AGROMETEO", "air_humidity_percent"),
    LEAF_WETNESS_HOURS("AGROMETEO", "leaf_wetness_hours");

    private final String sensorType;
    private final String jsonKey;

    AggregatableField(String sensorType, String jsonKey) {
        this.sensorType = sensorType;
        this.jsonKey = jsonKey;
    }

    public static Optional<AggregatableField> byJsonKey(String key) {
        return Arrays.stream(values()).filter(f -> f.jsonKey.equals(key)).findFirst();
    }
}

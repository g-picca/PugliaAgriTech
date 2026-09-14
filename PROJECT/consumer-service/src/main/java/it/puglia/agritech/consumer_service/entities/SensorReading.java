package it.puglia.agritech.consumer_service.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sensor_readings")
@Getter
@Setter
@NoArgsConstructor
public class SensorReading {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_id")
    private Sensor sensor;

    /**
     * Testo JSON grezzo del campo "data" del payload del sensore, mappato
     * direttamente sulla colonna jsonb senza passare da un oggetto Java
     * intermedio (nessuna riserializzazione: ciò che è stato validato è
     * ciò che viene salvato).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "received_at", insertable = false, updatable = false)
    private Instant receivedAt;

    private Boolean processed;
}

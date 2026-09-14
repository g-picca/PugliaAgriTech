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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sensors")
@Getter
@Setter
@NoArgsConstructor
public class Sensor {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "device_id", nullable = false, unique = true)
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_type_id")
    private SensorType sensorType;

    private String location;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}

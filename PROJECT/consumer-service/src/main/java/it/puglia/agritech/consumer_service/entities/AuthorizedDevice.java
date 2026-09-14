package it.puglia.agritech.consumer_service.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Allowlist dei device_id autorizzati all'auto-registrazione in 'sensors'.
 * Una firma HMAC valida da sola non prova l'identità del device (la chiave
 * è condivisa tra tutti i sensori): questa tabella è il controllo che
 * restringe l'auto-provisioning ai soli device noti a priori.
 */
@Entity
@Table(name = "authorized_devices")
@Getter
@Setter
@NoArgsConstructor
public class AuthorizedDevice {

    @Id
    @Column(name = "device_id")
    private String deviceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sensor_type_id", nullable = false)
    private SensorType sensorType;

    private String location;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private Boolean active;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}

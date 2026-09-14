package it.puglia.agritech.consumer_service.entities;

import it.puglia.agritech.consumer_service.enums.IngestionEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingestion_log")
@Getter
@Setter
@NoArgsConstructor
public class IngestionLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "device_id")
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private IngestionEventType eventType;

    private String detail;

    @Column(name = "occurred_at", insertable = false, updatable = false)
    private Instant occurredAt;
}

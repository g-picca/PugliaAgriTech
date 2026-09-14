package it.puglia.agritech.consumer_service.repositories;

import it.puglia.agritech.consumer_service.entities.IngestionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface IngestionLogRepository extends JpaRepository<IngestionLog, UUID> {
}

package it.puglia.agritech.consumer_service.repositories;

import it.puglia.agritech.consumer_service.entities.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SensorRepository extends JpaRepository<Sensor, UUID> {
    Optional<Sensor> findByDeviceId(String deviceId);
}

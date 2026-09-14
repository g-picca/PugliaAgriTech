package it.puglia.agritech.consumer_service.repositories;

import it.puglia.agritech.consumer_service.entities.SensorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SensorTypeRepository extends JpaRepository<SensorType, Integer> {
    Optional<SensorType> findByName(String name);
}

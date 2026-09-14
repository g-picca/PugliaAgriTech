package it.puglia.agritech.consumer_service.repositories;

import it.puglia.agritech.consumer_service.entities.AuthorizedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthorizedDeviceRepository extends JpaRepository<AuthorizedDevice, String> {
    Optional<AuthorizedDevice> findByDeviceIdAndActiveTrue(String deviceId);
}

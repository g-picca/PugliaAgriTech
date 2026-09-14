-- ALLOWLIST DEI DEVICE AUTORIZZATI A ESSERE AUTO-REGISTRATI IN 'sensors'
-- Popolata a priori (fase di "commissioning"): un device_id qui presente e
-- attivo può essere auto-provisionato al primo messaggio con firma HMAC
-- valida. La sola firma HMAC valida NON basta da sola come autorizzazione,
-- perché la chiave HMAC è condivisa tra tutti i sensori: questa tabella
-- restringe l'auto-provisioning ai soli device_id conosciuti in anticipo.
CREATE TABLE authorized_devices
(
    device_id      VARCHAR(255) PRIMARY KEY,
    sensor_type_id INT REFERENCES sensor_types (id) NOT NULL,
    location       VARCHAR(255),
    latitude       DECIMAL(9, 6),
    longitude      DECIMAL(9, 6),
    active         BOOLEAN   DEFAULT TRUE,
    created_at     TIMESTAMP DEFAULT NOW()
);

-- Device dei sensori simulati attualmente in esecuzione (sensors/docker-compose.yml)
INSERT INTO authorized_devices (device_id, sensor_type_id, location)
SELECT 'OLIVO_SEC_001', id, 'Settore Sud'
FROM sensor_types
WHERE name = 'TREE_TALKER';

INSERT INTO authorized_devices (device_id, sensor_type_id, location)
SELECT 'AGRO_STATION_SUD', id, 'Stazione Sud'
FROM sensor_types
WHERE name = 'AGROMETEO';

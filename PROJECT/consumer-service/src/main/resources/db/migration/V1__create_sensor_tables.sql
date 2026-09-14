-- TIPI DI SENSORI DISPONIBILI
CREATE TABLE sensor_types
(
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    created_at  TIMESTAMP DEFAULT NOW()
);

-- REGISTRO SENSORI INSTALLATI
CREATE TABLE sensors
(
    id             UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    device_id      VARCHAR(255) NOT NULL UNIQUE,
    sensor_type_id INT REFERENCES sensor_types (id),
    location       VARCHAR(255),
    latitude       DECIMAL(9, 6),
    longitude      DECIMAL(9, 6),
    active         BOOLEAN          DEFAULT TRUE,
    created_at     TIMESTAMP        DEFAULT NOW()
);

-- LETTURE DEI SENSORI
CREATE TABLE sensor_readings
(
    id          UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    sensor_id   UUID REFERENCES sensors (id),
    payload     JSONB NOT NULL,
    received_at TIMESTAMP        DEFAULT NOW(),
    processed   BOOLEAN          DEFAULT FALSE
);

-- INDICI PER QUERY FREQUENTI
CREATE INDEX idx_sensor_readings_received_at ON sensor_readings (received_at DESC);
CREATE INDEX idx_sensor_readings_sensor_id ON sensor_readings (sensor_id, received_at DESC);

-- TIPI DI SENSORI
INSERT INTO sensor_types (name, description)
VALUES ('TREE_TALKER', 'Sensore su tronco per monitoraggio Xylella - misura sap flow e umidità fusto'),
       ('AGROMETEO', 'Stazione agrometeorologica - misura temperatura, umidità, bagnatura fogliare');
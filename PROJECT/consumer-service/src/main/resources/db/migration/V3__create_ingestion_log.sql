-- LOG DI INGESTION: traccia ogni messaggio sensore ricevuto dal listener,
-- accettato o respinto, per monitoraggio operativo e audit di sicurezza
-- (es. tentativi ripetuti di firma non valida o device non autorizzato).
CREATE TABLE ingestion_log
(
    id          UUID PRIMARY KEY DEFAULT pg_catalog.gen_random_uuid(),
    device_id   VARCHAR(255),
    event_type  VARCHAR(50) NOT NULL,
    detail      TEXT,
    occurred_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_ingestion_log_occurred_at ON ingestion_log (occurred_at DESC);
CREATE INDEX idx_ingestion_log_event_type ON ingestion_log (event_type);
CREATE INDEX idx_ingestion_log_device_id ON ingestion_log (device_id);

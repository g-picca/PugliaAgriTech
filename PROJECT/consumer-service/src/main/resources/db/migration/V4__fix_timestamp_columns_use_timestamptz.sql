-- BUG REALE: tutte le colonne timestamp erano TIMESTAMP (senza fuso orario),
-- con DEFAULT NOW() valutato lato Postgres al momento dell'INSERT. Il
-- fuso orario di sessione usato da NOW() dipende da chi si connette: il
-- driver JDBC di Spring, in esecuzione su Windows, negozia il fuso orario
-- di default della JVM ("W. Europe Standard Time", CEST = UTC+2 in questo
-- periodo dell'anno); una sessione psql avviata direttamente nel container
-- usa invece il fuso orario di default del container Postgres (UTC). Stesso
-- istante, due valori "now" diversi a seconda di chi lo chiede — e poiché
-- TIMESTAMP scarta l'informazione di fuso orario dopo averla usata per
-- calcolare l'ora locale, i valori salvati (in orario Europe/Rome) non
-- sono più confrontabili in modo affidabile con un java.time.Instant
-- (sempre UTC) bindato da query successive: un confronto "received_at <
-- cutoff" tra un valore locale e un cutoff UTC può risultare falso anche
-- quando cronologicamente received_at precede davvero cutoff.
--
-- Sintomo osservato: la query di aggiornamento "mark processed" della
-- generazione del report giornaliero non trovava mai righe da aggiornare
-- (0 righe), nonostante letture realmente non processate e più vecchie del
-- cutoff fossero presenti — lo sfasamento di 2 ore le faceva risultare
-- (erroneamente) più recenti del cutoff.
--
-- Fix strutturale: TIMESTAMPTZ memorizza sempre un istante assoluto
-- (internamente in UTC), indipendente dal fuso orario di sessione di chi
-- legge o scrive — esattamente la garanzia che serve per confrontare in
-- modo affidabile con java.time.Instant. I dati storici, scritti tutti
-- nella sessione JDBC (quindi in orario Europe/Rome), vengono reinterpretati
-- correttamente in fase di conversione con "AT TIME ZONE 'Europe/Rome'".
ALTER TABLE sensor_types
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Europe/Rome';

ALTER TABLE sensors
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Europe/Rome';

ALTER TABLE sensor_readings
    ALTER COLUMN received_at TYPE TIMESTAMPTZ USING received_at AT TIME ZONE 'Europe/Rome';

ALTER TABLE authorized_devices
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Europe/Rome';

ALTER TABLE ingestion_log
    ALTER COLUMN occurred_at TYPE TIMESTAMPTZ USING occurred_at AT TIME ZONE 'Europe/Rome';

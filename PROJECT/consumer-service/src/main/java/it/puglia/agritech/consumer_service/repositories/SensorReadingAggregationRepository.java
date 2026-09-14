package it.puglia.agritech.consumer_service.repositories;

import it.puglia.agritech.consumer_service.entities.Sensor;
import it.puglia.agritech.consumer_service.entities.SensorReading;
import it.puglia.agritech.consumer_service.entities.SensorType;
import it.puglia.agritech.consumer_service.enums.AggregatableField;
import it.puglia.agritech.consumer_service.enums.AggregationType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.hibernate.query.criteria.JpaExpression;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Query di aggregazione su sensor_readings tramite Criteria API, non
 * esprimibile con i metodi derivati di Spring Data JPA (la funzione di
 * aggregazione e il campo JSON variano a runtime, e nessuna delle due cose
 * si può bindare come parametro: vanno composte come parte della query
 * stessa, qui costruita ad albero di espressioni tipizzate invece che come
 * testo SQL concatenato).
 * <p>
 * Sicurezza: come nella versione a query nativa che questa sostituisce,
 * la funzione di aggregazione e il nome del campo entrano nella query solo
 * se già validati a monte da {@link AggregationType#valueOf} e
 * {@link AggregatableField#byJsonKey} — mai a partire dalla stringa grezza
 * della richiesta. Il nome campo, qui, è addirittura passato come parametro
 * vero e proprio della funzione ({@code cb.literal(...)}, bindato dal
 * driver) e non più concatenato nel testo della query: un livello di difesa
 * in più rispetto alla versione nativa, non necessario data la whitelist a
 * monte, ma "gratuito" con questo approccio.
 */
@Repository
public class SensorReadingAggregationRepository {

    private final EntityManager entityManager;

    public SensorReadingAggregationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public record AggregationResult(Double value, long sampleCount) {
    }

    /**
     * @param from             inizio intervallo, usato solo se unprocessedCutoff è null
     * @param to               fine intervallo, usato solo se unprocessedCutoff è null
     * @param unprocessedCutoff se valorizzato, ignora from/to e filtra invece
     *                          su processed=false AND receivedAt < unprocessedCutoff
     *                          (modalità "dati non ancora processati", usata
     *                          dalla generazione del report giornaliero)
     */
    public AggregationResult aggregate(
            AggregationType aggregation,
            AggregatableField field,
            String deviceId,
            String sensorTypeName,
            Instant from,
            Instant to,
            Instant unprocessedCutoff
    ) {
        // HibernateCriteriaBuilder (estensione Hibernate del CriteriaBuilder
        // standard JPA) serve per cast(...): non esiste nell'interfaccia JPA
        // standard. Coerente con l'uso già Hibernate-specifico di
        // @JdbcTypeCode(SqlTypes.JSON) sull'entità SensorReading: il progetto
        // non punta alla portabilità verso un altro provider JPA.
        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();

        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<SensorReading> reading = query.from(SensorReading.class);
        Join<SensorReading, Sensor> sensor = reading.join("sensor");
        Join<Sensor, SensorType> sensorType = sensor.join("sensorType");

        List<Predicate> predicates = new ArrayList<>();
        if (unprocessedCutoff != null) {
            predicates.add(cb.isFalse(reading.get("processed")));
            predicates.add(cb.lessThan(reading.get("receivedAt"), unprocessedCutoff));
        } else {
            predicates.add(cb.between(reading.get("receivedAt"), from, to));
        }
        if (deviceId != null) {
            predicates.add(cb.equal(sensor.get("deviceId"), deviceId));
        }
        if (sensorTypeName != null) {
            predicates.add(cb.equal(sensorType.get("name"), sensorTypeName));
        }

        // Due espressioni COUNT indipendenti (non la stessa riusata con due
        // alias): alias(...) muta l'alias sul nodo stesso invece di crearne
        // una copia, quindi riusare la stessa istanza per "value" e poi per
        // "sampleCount" sovrascrive il primo alias con il secondo, causando
        // un alias duplicato nel multiselect.
        Selection<?> countSelection = cb.count(reading).alias("sampleCount");
        Selection<?> valueSelection;

        if (aggregation == AggregationType.COUNT) {
            valueSelection = cb.count(reading).alias("value");
        } else {
            // L'operatore Postgres ->> (estrazione testo da jsonb) non ha un
            // equivalente diretto nello standard JPA/Criteria: si invoca come
            // funzione nativa tramite l'escape hatch CriteriaBuilder.function,
            // pensato esattamente per questi casi.
            JpaExpression<String> jsonText = cb.function(
                    "jsonb_extract_path_text", String.class,
                    reading.get("payload"), cb.literal(field.getJsonKey()));
            JpaExpression<Double> numeric = cb.cast(jsonText, Double.class);

            JpaExpression<Double> aggExpr = switch (aggregation) {
                case AVG -> cb.avg(numeric);
                case MIN -> cb.min(numeric);
                case MAX -> cb.max(numeric);
                case COUNT -> throw new IllegalStateException("COUNT gestito separatamente sopra");
            };
            valueSelection = aggExpr.alias("value");
        }

        // CriteriaQuery.multiselect(...) è deprecato da Jakarta Persistence
        // 3.2 (entrambi gli overload, varargs e List): il sostituto indicato
        // dalla specifica è comporre le selezioni con CriteriaBuilder.array(...)
        // (o .tuple(...)/.construct(...) per altri tipi di risultato) e passarle
        // a query.select(...).
        query.select(cb.array(valueSelection, countSelection))
                .where(predicates.toArray(new Predicate[0]));

        TypedQuery<Object[]> typedQuery = entityManager.createQuery(query);
        Object[] row = typedQuery.getSingleResult();
        Double value = row[0] == null ? null : ((Number) row[0]).doubleValue();
        long count = ((Number) row[1]).longValue();
        return new AggregationResult(value, count);
    }

    /**
     * Marca come processed le letture non ancora processate, ricevute prima
     * di 'cutoff', che soddisfano il filtro (stesso significato di
     * deviceId/sensorTypeName in aggregate(...)).
     * <p>
     * Una CriteriaUpdate (come qualunque UPDATE/DELETE bulk in JPA) non può
     * fare join diretti dalla root: per filtrare sensor_readings in base a
     * un attributo di Sensor/SensorType si passa da una subquery che
     * seleziona gli id dei sensori corrispondenti, non da un
     * Root.join(...) come nella query di sola lettura sopra.
     */
    @Transactional
    public long markProcessed(String deviceId, String sensorTypeName, Instant cutoff) {
        HibernateCriteriaBuilder cb = (HibernateCriteriaBuilder) entityManager.getCriteriaBuilder();

        CriteriaUpdate<SensorReading> update = cb.createCriteriaUpdate(SensorReading.class);
        Root<SensorReading> reading = update.from(SensorReading.class);
        update.set(reading.get("processed"), true);

        List<Predicate> predicates = new ArrayList<>();
        predicates.add(cb.isFalse(reading.get("processed")));
        predicates.add(cb.lessThan(reading.get("receivedAt"), cutoff));

        if (deviceId != null || sensorTypeName != null) {
            Subquery<UUID> matchingSensorIds = update.subquery(UUID.class);
            Root<Sensor> sensorRoot = matchingSensorIds.from(Sensor.class);
            matchingSensorIds.select(sensorRoot.get("id"));

            List<Predicate> sensorPredicates = new ArrayList<>();
            if (deviceId != null) {
                sensorPredicates.add(cb.equal(sensorRoot.get("deviceId"), deviceId));
            }
            if (sensorTypeName != null) {
                Join<Sensor, SensorType> sensorType = sensorRoot.join("sensorType");
                sensorPredicates.add(cb.equal(sensorType.get("name"), sensorTypeName));
            }
            matchingSensorIds.where(sensorPredicates.toArray(new Predicate[0]));

            // reading.get("sensor").get("id") legge la colonna FK direttamente
            // (è l'id di una ManyToOne, non richiede un join reale): confrontarla
            // con la subquery è l'equivalente JPA di "sensor_id IN (SELECT id ...)".
            predicates.add(reading.get("sensor").get("id").in(matchingSensorIds));
        }

        update.where(predicates.toArray(new Predicate[0]));
        return entityManager.createQuery(update).executeUpdate();
    }
}

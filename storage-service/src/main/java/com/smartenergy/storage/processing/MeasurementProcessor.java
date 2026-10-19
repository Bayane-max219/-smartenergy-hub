package com.smartenergy.storage.processing;

import com.smartenergy.storage.cassandra.MeasurementStore;
import com.smartenergy.storage.model.Measurement;
import com.smartenergy.storage.mongo.AggregateStore;
import com.smartenergy.storage.mongo.Sensor;
import com.smartenergy.storage.mongo.SensorRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Persiste une mesure : Cassandra (vérité) puis MongoDB (agrégat) puis cache Redis.
 *
 * <p>Idempotence : l'INSERT Cassandra est conditionnel (IF NOT EXISTS). Un événement rejoué
 * (même capteur, même instant) est détecté comme doublon et n'altère donc pas les agrégats.
 */
@Service
public class MeasurementProcessor {

    public enum Outcome { STORED, DUPLICATE }

    private final MeasurementStore store;
    private final SensorRegistry sensors;
    private final AggregateStore aggregates;
    private final LatestCache cache;
    private final Counter stored;
    private final Counter duplicates;
    private final Timer processTime;

    public MeasurementProcessor(MeasurementStore store, SensorRegistry sensors, AggregateStore aggregates,
                                LatestCache cache, MeterRegistry registry) {
        this.store = store;
        this.sensors = sensors;
        this.aggregates = aggregates;
        this.cache = cache;
        this.stored = Counter.builder("smartenergy.measurements.stored")
                .description("Mesures persistées").register(registry);
        this.duplicates = Counter.builder("smartenergy.measurements.duplicates")
                .description("Doublons ignorés").register(registry);
        this.processTime = Timer.builder("smartenergy.measurements.process.time")
                .description("Temps de persistance d'une mesure").register(registry);
    }

    public Outcome process(Measurement m) {
        return processTime.record(() -> doProcess(m));
    }

    private Outcome doProcess(Measurement m) {
        if (!store.insertIfAbsent(m)) {
            duplicates.increment();
            return Outcome.DUPLICATE;
        }
        Sensor sensor = sensors.ensureExists(m.sensorId());
        boolean anomaly = sensor.maxPowerKw() != null && m.powerKw() > sensor.maxPowerKw();
        aggregates.record(m, anomaly);
        cache.update(m);
        stored.increment();
        return Outcome.STORED;
    }
}

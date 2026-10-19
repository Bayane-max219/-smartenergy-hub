package com.smartenergy.storage.cassandra;

import com.smartenergy.storage.model.Measurement;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.InsertOptions;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class MeasurementStore {

    private static final InsertOptions IF_NOT_EXISTS = InsertOptions.builder().withIfNotExists().build();

    private final CassandraTemplate template;

    public MeasurementStore(CassandraTemplate template) {
        this.template = template;
    }

    /**
     * Insère la mesure sauf si (capteur, jour, instant) existe déjà (LWT).
     *
     * @return true si la ligne est nouvelle, false si c'était un doublon
     */
    public boolean insertIfAbsent(Measurement m) {
        MeasurementEntity.Key key = new MeasurementEntity.Key(m.sensorId(), dayOf(m.timestamp()), m.timestamp());
        MeasurementEntity entity = new MeasurementEntity(key, m.powerKw(), m.voltage(), m.temperature());
        return template.insert(entity, IF_NOT_EXISTS).wasApplied();
    }

    /** Mesures dans [from, to], les plus récentes d'abord, une requête par partition journalière. */
    public List<Measurement> findBetween(String sensorId, Instant from, Instant to, int limit) {
        List<Measurement> result = new ArrayList<>();
        LocalDate firstDay = dayOf(from);
        for (LocalDate day = dayOf(to); !day.isBefore(firstDay) && result.size() < limit; day = day.minusDays(1)) {
            Query query = Query.query(
                            Criteria.where("sensor_id").is(sensorId),
                            Criteria.where("day").is(day),
                            Criteria.where("measured_at").gte(from),
                            Criteria.where("measured_at").lte(to))
                    .limit(limit - result.size());
            for (MeasurementEntity e : template.select(query, MeasurementEntity.class)) {
                result.add(toMeasurement(e));
            }
        }
        return result;
    }

    /** Dernière mesure connue en remontant au plus {@code lookbackDays} jours. */
    public Optional<Measurement> findLatest(String sensorId, int lookbackDays) {
        Instant now = Instant.now();
        List<Measurement> found = findBetween(sensorId, now.minusSeconds(lookbackDays * 86_400L), now, 1);
        return found.stream().findFirst();
    }

    public static LocalDate dayOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static Measurement toMeasurement(MeasurementEntity e) {
        return new Measurement(e.getKey().getSensorId(), e.getKey().getMeasuredAt(),
                e.getPowerKw(), e.getVoltage(), e.getTemperature());
    }
}

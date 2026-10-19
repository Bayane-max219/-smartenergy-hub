package com.smartenergy.storage.mongo;

import com.smartenergy.storage.cassandra.MeasurementStore;
import com.smartenergy.storage.model.Measurement;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

@Service
public class AggregateStore {

    private final MongoTemplate mongo;

    public AggregateStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Mise à jour atomique ($inc/$min/$max) de l'agrégat du jour de la mesure. */
    public void record(Measurement m, boolean anomaly) {
        LocalDate day = MeasurementStore.dayOf(m.timestamp());
        Update update = new Update()
                .setOnInsert("sensorId", m.sensorId())
                .setOnInsert("date", day.toString())
                .inc("count", 1)
                .inc("sumPowerKw", m.powerKw())
                .inc("anomalyCount", anomaly ? 1 : 0)
                .min("minPowerKw", m.powerKw())
                .max("maxPowerKw", m.powerKw());
        mongo.upsert(Query.query(Criteria.where("_id").is(id(m.sensorId(), day))), update, DailyAggregate.class);
    }

    public Optional<DailyAggregate> find(String sensorId, LocalDate day) {
        return Optional.ofNullable(mongo.findById(id(sensorId, day), DailyAggregate.class));
    }

    private static String id(String sensorId, LocalDate day) {
        return sensorId + "|" + day;
    }
}

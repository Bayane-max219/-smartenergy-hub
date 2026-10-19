package com.smartenergy.storage.mongo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SensorRegistry {

    private final MongoTemplate mongo;
    private final double defaultMaxPowerKw;

    public SensorRegistry(MongoTemplate mongo,
                          @Value("${smartenergy.sensors.default-max-power-kw:8.0}") double defaultMaxPowerKw) {
        this.mongo = mongo;
        this.defaultMaxPowerKw = defaultMaxPowerKw;
    }

    /** Retourne le capteur, en le créant avec des valeurs par défaut s'il est inconnu (une seule requête). */
    public Sensor ensureExists(String sensorId) {
        Update onInsert = new Update()
                .setOnInsert("name", sensorId)
                .setOnInsert("maxPowerKw", defaultMaxPowerKw)
                .setOnInsert("active", true)
                .setOnInsert("tags", List.of("auto-registered"));
        return mongo.findAndModify(byId(sensorId), onInsert,
                FindAndModifyOptions.options().upsert(true).returnNew(true), Sensor.class);
    }

    public Optional<Sensor> find(String sensorId) {
        return Optional.ofNullable(mongo.findById(sensorId, Sensor.class));
    }

    public List<Sensor> findAll() {
        return new ArrayList<>(mongo.findAll(Sensor.class));
    }

    public boolean exists(String sensorId) {
        return mongo.exists(byId(sensorId), Sensor.class);
    }

    public Sensor save(Sensor sensor) {
        return mongo.save(sensor);
    }

    private static Query byId(String sensorId) {
        return Query.query(Criteria.where("_id").is(sensorId));
    }
}

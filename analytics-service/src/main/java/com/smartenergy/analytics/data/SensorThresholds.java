package com.smartenergy.analytics.data;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** Seuil de puissance (maxPowerKw) d'un capteur, lu dans la collection MongoDB "sensors". */
@Service
public class SensorThresholds {

    private final MongoTemplate mongo;
    private final double defaultMaxPowerKw;

    public SensorThresholds(MongoTemplate mongo,
                            @Value("${smartenergy.analytics.default-max-power-kw:8.0}") double defaultMaxPowerKw) {
        this.mongo = mongo;
        this.defaultMaxPowerKw = defaultMaxPowerKw;
    }

    public double maxPowerKw(String sensorId) {
        Document doc = mongo.findById(sensorId, Document.class, "sensors");
        if (doc != null && doc.get("maxPowerKw") instanceof Number n) {
            return n.doubleValue();
        }
        return defaultMaxPowerKw;
    }
}

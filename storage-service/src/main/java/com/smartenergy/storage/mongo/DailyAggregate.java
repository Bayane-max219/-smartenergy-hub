package com.smartenergy.storage.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Agrégat quotidien incrémental (collection "daily_aggregates"), _id = "capteur|yyyy-MM-dd". */
@Document("daily_aggregates")
public record DailyAggregate(
        @Id String id,
        String sensorId,
        String date,
        long count,
        double sumPowerKw,
        double minPowerKw,
        double maxPowerKw,
        long anomalyCount) {

    public double avgPowerKw() {
        return count == 0 ? 0 : sumPowerKw / count;
    }
}

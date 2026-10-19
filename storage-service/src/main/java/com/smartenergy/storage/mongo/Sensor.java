package com.smartenergy.storage.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/** Métadonnées d'un capteur (collection "sensors"). */
@Document("sensors")
public record Sensor(
        @Id String sensorId,
        String name,
        String building,
        Integer floor,
        Double maxPowerKw,
        Boolean active,
        List<String> tags) {
}

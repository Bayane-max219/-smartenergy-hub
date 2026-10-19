package com.smartenergy.storage.api;

import com.smartenergy.storage.mongo.Sensor;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Métadonnées d'un capteur")
public record SensorRequest(
        @Schema(example = "SENSOR-001") @NotBlank @Size(max = 64) String sensorId,
        @Schema(example = "Compteur salle serveur") String name,
        @Schema(example = "Bâtiment A") String building,
        @Schema(example = "1") Integer floor,
        @Schema(description = "Seuil de dépassement en kW", example = "8.0") @Positive Double maxPowerKw,
        @Schema(example = "true") Boolean active,
        @Schema(example = "[\"critical\", \"server-room\"]") List<String> tags) {

    public Sensor toSensor(String id) {
        return new Sensor(id, name, building, floor, maxPowerKw, active == null || active,
                tags == null ? List.of() : tags);
    }
}

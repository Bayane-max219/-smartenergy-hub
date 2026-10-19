package com.smartenergy.collector;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(description = "Mesure énergétique envoyée par un capteur")
public record EnergyMeasurementRequest(
        @Schema(example = "SENSOR-001")
        @NotBlank @Size(max = 64) String sensorId,

        @Schema(description = "Horodatage UTC (ISO-8601)", example = "2026-09-30T08:15:00Z")
        @NotNull Instant timestamp,

        @Schema(description = "Puissance en kW", example = "3.72")
        @NotNull @PositiveOrZero @DecimalMax("100000.0") Double powerKw,

        @Schema(description = "Tension en V", example = "226.4")
        @NotNull @DecimalMin("0.0") @DecimalMax("1000.0") Double voltage,

        @Schema(description = "Température ambiante en °C (optionnelle)", example = "29.1")
        @DecimalMin("-60.0") @DecimalMax("150.0") Double temperature
) {
}

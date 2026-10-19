package com.smartenergy.storage.model;

import java.time.Instant;

/** Mesure énergétique validée. {@code temperature} peut être null. */
public record Measurement(String sensorId, Instant timestamp, double powerKw, double voltage, Double temperature) {
}

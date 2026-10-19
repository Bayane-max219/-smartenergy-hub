package com.smartenergy.storage.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Convertit les champs d'un événement Redis en {@link Measurement}. */
public final class MeasurementParser {

    private MeasurementParser() {
    }

    public static Measurement parse(Map<?, ?> fields) {
        String sensorId = text(fields, "sensorId");
        if (sensorId == null || sensorId.isBlank()) {
            throw new InvalidMeasurementException("sensorId manquant");
        }
        String ts = text(fields, "timestamp");
        if (ts == null) {
            throw new InvalidMeasurementException("timestamp manquant");
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(ts);
        } catch (DateTimeParseException e) {
            throw new InvalidMeasurementException("timestamp illisible: " + ts);
        }
        double power = number(fields, "powerKw", true);
        double voltage = number(fields, "voltage", true);
        if (power < 0) {
            throw new InvalidMeasurementException("powerKw négatif: " + power);
        }
        Double temperature = text(fields, "temperature") == null ? null : number(fields, "temperature", false);
        return new Measurement(sensorId, timestamp, power, voltage, temperature);
    }

    private static String text(Map<?, ?> fields, String key) {
        Object v = fields.get(key);
        return v == null ? null : v.toString();
    }

    private static double number(Map<?, ?> fields, String key, boolean required) {
        String raw = text(fields, key);
        if (raw == null) {
            if (required) {
                throw new InvalidMeasurementException(key + " manquant");
            }
            return Double.NaN;
        }
        try {
            double d = Double.parseDouble(raw);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new InvalidMeasurementException(key + " non fini: " + raw);
            }
            return d;
        } catch (NumberFormatException e) {
            throw new InvalidMeasurementException(key + " non numérique: " + raw);
        }
    }
}

package com.smartenergy.storage;

import com.smartenergy.storage.model.InvalidMeasurementException;
import com.smartenergy.storage.model.Measurement;
import com.smartenergy.storage.model.MeasurementParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeasurementParserTest {

    private static Map<Object, Object> valid() {
        Map<Object, Object> m = new HashMap<>();
        m.put("sensorId", "SENSOR-001");
        m.put("timestamp", "2026-09-30T08:15:00Z");
        m.put("powerKw", "3.72");
        m.put("voltage", "226.4");
        m.put("temperature", "29.1");
        return m;
    }

    @Test
    void parsesAValidEvent() {
        Measurement m = MeasurementParser.parse(valid());
        assertEquals("SENSOR-001", m.sensorId());
        assertEquals(Instant.parse("2026-09-30T08:15:00Z"), m.timestamp());
        assertEquals(3.72, m.powerKw());
        assertEquals(29.1, m.temperature());
    }

    @Test
    void temperatureIsOptional() {
        Map<Object, Object> f = valid();
        f.remove("temperature");
        assertNull(MeasurementParser.parse(f).temperature());
    }

    @Test
    void rejectsMissingSensorId() {
        Map<Object, Object> f = valid();
        f.remove("sensorId");
        assertThrows(InvalidMeasurementException.class, () -> MeasurementParser.parse(f));
    }

    @Test
    void rejectsBadNumbersAndTimestamps() {
        Map<Object, Object> f = valid();
        f.put("powerKw", "abc");
        assertThrows(InvalidMeasurementException.class, () -> MeasurementParser.parse(f));

        Map<Object, Object> g = valid();
        g.put("timestamp", "hier");
        assertThrows(InvalidMeasurementException.class, () -> MeasurementParser.parse(g));

        Map<Object, Object> h = valid();
        h.put("powerKw", "-2");
        assertThrows(InvalidMeasurementException.class, () -> MeasurementParser.parse(h));
    }
}

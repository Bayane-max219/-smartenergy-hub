package com.smartenergy.storage;

import com.smartenergy.storage.cassandra.MeasurementStore;
import com.smartenergy.storage.model.Measurement;
import com.smartenergy.storage.mongo.AggregateStore;
import com.smartenergy.storage.mongo.Sensor;
import com.smartenergy.storage.mongo.SensorRegistry;
import com.smartenergy.storage.processing.LatestCache;
import com.smartenergy.storage.processing.MeasurementProcessor;
import com.smartenergy.storage.processing.MeasurementProcessor.Outcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeasurementProcessorTest {

    MeasurementStore store = mock(MeasurementStore.class);
    SensorRegistry sensors = mock(SensorRegistry.class);
    AggregateStore aggregates = mock(AggregateStore.class);
    LatestCache cache = mock(LatestCache.class);
    MeasurementProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new MeasurementProcessor(store, sensors, aggregates, cache, new SimpleMeterRegistry());
        when(sensors.ensureExists("S1")).thenReturn(new Sensor("S1", "S1", null, null, 8.0, true, List.of()));
    }

    private static Measurement measurement(double power) {
        return new Measurement("S1", Instant.parse("2026-09-30T08:15:00Z"), power, 230.0, 20.0);
    }

    @Test
    void newMeasurementIsStoredAndAggregated() {
        Measurement m = measurement(3.0);
        when(store.insertIfAbsent(m)).thenReturn(true);

        assertEquals(Outcome.STORED, processor.process(m));
        verify(aggregates).record(m, false);
        verify(cache).update(m);
    }

    @Test
    void powerAboveSensorThresholdCountsAsAnomaly() {
        Measurement m = measurement(12.0);
        when(store.insertIfAbsent(m)).thenReturn(true);

        processor.process(m);
        verify(aggregates).record(eq(m), eq(true));
    }

    @Test
    void duplicateDoesNotTouchAggregates() {
        Measurement m = measurement(3.0);
        when(store.insertIfAbsent(m)).thenReturn(false);

        assertEquals(Outcome.DUPLICATE, processor.process(m));
        verify(aggregates, never()).record(any(), anyBoolean());
        verify(cache, never()).update(any());
    }
}

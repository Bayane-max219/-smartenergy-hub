package com.smartenergy.storage;

import com.smartenergy.storage.model.Measurement;
import com.smartenergy.storage.processing.EnergyCalculator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnergyCalculatorTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    @Test
    void twoKwDuringOneHourIsTwoKwhEvenWhenInputIsNewestFirst() {
        List<Measurement> pts = new ArrayList<>();
        for (int i = 0; i <= 60; i++) {
            pts.add(new Measurement("S", T0.plusSeconds(i * 60L), 2.0, 230, null));
        }
        Collections.reverse(pts);   // Cassandra renvoie le plus récent d'abord
        assertEquals(2.0, EnergyCalculator.energyKwh(pts, 3600), 1e-9);
    }

    @Test
    void gapsAboveLimitAreIgnoredAndSingleOrEmptyGiveZero() {
        List<Measurement> pts = List.of(
                new Measurement("S", T0, 5.0, 230, null),
                new Measurement("S", T0.plusSeconds(7200), 5.0, 230, null));
        assertEquals(0.0, EnergyCalculator.energyKwh(pts, 3600));
        assertEquals(0.0, EnergyCalculator.energyKwh(List.of(), 3600));
    }
}

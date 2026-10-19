package com.smartenergy.analytics;

import com.smartenergy.analytics.compute.AnomalyDetector;
import com.smartenergy.analytics.compute.LinearForecaster;
import com.smartenergy.analytics.compute.Point;
import com.smartenergy.analytics.compute.Stats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    /** n points espacés de stepSeconds, puissance donnée par la fonction. */
    private static List<Point> series(int n, long stepSeconds, java.util.function.IntToDoubleFunction power) {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            pts.add(new Point(T0.plusSeconds(i * stepSeconds), power.applyAsDouble(i)));
        }
        return pts;
    }

    @Test
    void energyOfConstantTwoKwOverOneHourIsTwoKwh() {
        // 61 points/minute pendant 1 h à 2 kW -> 60 intervalles de 60 s
        List<Point> pts = series(61, 60, i -> 2.0);
        assertEquals(2.0, Stats.energyKwh(pts, 3600), 1e-9);
    }

    @Test
    void gapsLargerThanMaxGapAreNotCountedInEnergy() {
        List<Point> pts = List.of(
                new Point(T0, 2.0),
                new Point(T0.plusSeconds(60), 2.0),
                new Point(T0.plusSeconds(60 + 7200), 2.0));
        assertEquals(2.0 * 60 / 3600, Stats.energyKwh(pts, 3600), 1e-9);
    }

    @Test
    void summaryComputesAvgMinMaxAndExceedances() {
        List<Point> pts = series(4, 60, i -> new double[]{1, 2, 9, 4}[i]);
        Stats.Summary s = Stats.summarize(pts, 8.0, 3600);
        assertEquals(4, s.count());
        assertEquals(4.0, s.avgPowerKw(), 1e-9);
        assertEquals(1.0, s.minPowerKw());
        assertEquals(9.0, s.maxPowerKw());
        assertEquals(1, s.thresholdExceedances());
    }

    @Test
    void injectedSpikeIsDetectedByThresholdAndZScore() {
        List<Point> pts = series(100, 5, i -> i == 50 ? 12.0 : 3.0 + (i % 2) * 0.1);
        List<AnomalyDetector.Anomaly> found = AnomalyDetector.detect(pts, 8.0, 3.0);
        assertEquals(1, found.size());
        assertEquals(T0.plusSeconds(250), found.get(0).timestamp());
        assertTrue(found.get(0).aboveThreshold());
        assertTrue(found.get(0).zScoreOutlier());
    }

    @Test
    void flatSeriesHasNoAnomaly() {
        assertTrue(AnomalyDetector.detect(series(50, 5, i -> 3.0), 8.0, 3.0).isEmpty());
    }

    @Test
    void forecastRecoversALinearTrend() {
        List<Point> pts = series(50, 60, i -> 1.0 + 0.01 * i);   // +0.01 kW par minute
        LinearForecaster.Forecast f = LinearForecaster.forecast(pts, 3);
        assertEquals(0.0, f.mae(), 1e-6);
        assertEquals(3, f.predictions().size());
        assertEquals(60, f.stepSeconds());
        assertEquals(1.0 + 0.01 * 50, f.predictions().get(0).powerKw(), 1e-6);
    }

    @Test
    void forecastNeedsEnoughPoints() {
        assertThrows(IllegalArgumentException.class, () -> LinearForecaster.forecast(series(5, 60, i -> 1.0), 3));
    }
}

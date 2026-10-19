package com.smartenergy.analytics.compute;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Détection d'anomalies (niveau B) : dépassement de seuil et score z standardisé. */
public final class AnomalyDetector {

    private AnomalyDetector() {
    }

    public record Anomaly(Instant timestamp, double powerKw, boolean aboveThreshold,
                          boolean zScoreOutlier, double zScore) {
    }

    /**
     * Une mesure est anormale si P_t &gt; seuil <em>ou</em> |z_t| &gt; zThreshold, avec
     * z_t = (P_t - μ) / σ calculés sur la période demandée (σ population).
     */
    public static List<Anomaly> detect(List<Point> points, double thresholdKw, double zThreshold) {
        double mean = points.stream().mapToDouble(Point::powerKw).average().orElse(0);
        double variance = points.stream().mapToDouble(p -> (p.powerKw() - mean) * (p.powerKw() - mean))
                .average().orElse(0);
        double std = Math.sqrt(variance);

        List<Anomaly> anomalies = new ArrayList<>();
        for (Point p : points) {
            double z = std == 0 ? 0 : (p.powerKw() - mean) / std;
            boolean above = p.powerKw() > thresholdKw;
            boolean outlier = Math.abs(z) > zThreshold;
            if (above || outlier) {
                anomalies.add(new Anomaly(p.timestamp(), p.powerKw(), above, outlier, z));
            }
        }
        return anomalies;
    }
}

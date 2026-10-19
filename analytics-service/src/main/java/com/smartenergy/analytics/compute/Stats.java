package com.smartenergy.analytics.compute;

import java.time.Duration;
import java.util.List;

/** Analyse descriptive (niveau A). */
public final class Stats {

    private Stats() {
    }

    public record Summary(long count, double avgPowerKw, double minPowerKw, double maxPowerKw,
                          double energyKwh, long thresholdExceedances) {
    }

    /**
     * @param points        triés par date croissante (non vide)
     * @param thresholdKw   seuil de dépassement
     * @param maxGapSeconds intervalle maximal pris en compte dans l'énergie (évite de compter les trous)
     */
    public static Summary summarize(List<Point> points, double thresholdKw, long maxGapSeconds) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Aucune mesure");
        }
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        long exceed = 0;
        for (Point p : points) {
            sum += p.powerKw();
            min = Math.min(min, p.powerKw());
            max = Math.max(max, p.powerKw());
            if (p.powerKw() > thresholdKw) {
                exceed++;
            }
        }
        return new Summary(points.size(), sum / points.size(), min, max,
                energyKwh(points, maxGapSeconds), exceed);
    }

    /** E ≈ Σ P_i · Δt_i avec Δt_i = t_{i+1} - t_i en heures ; le dernier point n'a pas d'intervalle. */
    public static double energyKwh(List<Point> points, long maxGapSeconds) {
        double energy = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            long dt = Duration.between(points.get(i).timestamp(), points.get(i + 1).timestamp()).getSeconds();
            if (dt > 0 && dt <= maxGapSeconds) {
                energy += points.get(i).powerKw() * dt / 3600.0;
            }
        }
        return energy;
    }
}

package com.smartenergy.storage.processing;

import com.smartenergy.storage.model.Measurement;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/** Énergie estimée E ≈ Σ P_i · Δt_i (kWh) ; les trous supérieurs à maxGapSeconds ne sont pas comptés. */
public final class EnergyCalculator {

    private EnergyCalculator() {
    }

    public static double energyKwh(List<Measurement> measurements, long maxGapSeconds) {
        List<Measurement> sorted = measurements.stream()
                .sorted(Comparator.comparing(Measurement::timestamp)).toList();
        double energy = 0;
        for (int i = 0; i + 1 < sorted.size(); i++) {
            long dt = Duration.between(sorted.get(i).timestamp(), sorted.get(i + 1).timestamp()).getSeconds();
            if (dt > 0 && dt <= maxGapSeconds) {
                energy += sorted.get(i).powerKw() * dt / 3600.0;
            }
        }
        return energy;
    }
}

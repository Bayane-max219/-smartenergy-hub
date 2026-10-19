package com.smartenergy.analytics.compute;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Prévision de référence (niveau C, version simple) : régression linéaire P(t) = a·t + b.
 *
 * <p>Démarche : séparation chronologique 80 % entraînement / 20 % test, mesure de MAE et RMSE
 * sur le test pour la régression linéaire et pour une baseline « persistance » (dernière valeur
 * connue), puis sélection du meilleur des deux (on ne propose jamais un modèle pire que la baseline),
 * ré-entraînement sur tout l'historique et prévision de {@code horizon} pas.
 * Limite : une tendance linéaire ne capture pas la saisonnalité (cycle jour/nuit).
 */
public final class LinearForecaster {

    public static final int MIN_POINTS = 10;

    private LinearForecaster() {
    }

    public record Prediction(Instant timestamp, double powerKw) {
    }

    /** mae/rmse sont ceux du modèle retenu ; linearMae et baselineMae permettent de comparer les deux. */
    public record Forecast(String model, int trainSize, int testSize, double mae, double rmse,
                           double linearMae, double baselineMae, long stepSeconds, List<Prediction> predictions) {
    }

    public static Forecast forecast(List<Point> points, int horizon) {
        if (points.size() < MIN_POINTS) {
            throw new IllegalArgumentException(
                    "Au moins " + MIN_POINTS + " mesures sont nécessaires pour une prévision (reçu " + points.size() + ")");
        }
        int split = (int) Math.floor(points.size() * 0.8);
        List<Point> train = points.subList(0, split);
        List<Point> test = points.subList(split, points.size());
        Instant origin = points.get(0).timestamp();

        double[] coef = fit(train, origin);
        double absSum = 0;
        double sqSum = 0;
        double baselineAbs = 0;
        double baselineSq = 0;
        double lastTrain = train.get(train.size() - 1).powerKw();
        for (Point p : test) {
            double predicted = coef[0] * seconds(origin, p.timestamp()) + coef[1];
            double err = p.powerKw() - predicted;
            absSum += Math.abs(err);
            sqSum += err * err;
            double baseErr = p.powerKw() - lastTrain;
            baselineAbs += Math.abs(baseErr);
            baselineSq += baseErr * baseErr;
        }
        int n = test.size();
        double linearMae = absSum / n;
        double baselineMae = baselineAbs / n;
        boolean linearWins = linearMae <= baselineMae;

        double[] full = fit(points, origin);
        long step = medianStepSeconds(points);
        Instant last = points.get(points.size() - 1).timestamp();
        double lastValue = points.get(points.size() - 1).powerKw();
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 1; i <= horizon; i++) {
            Instant t = last.plusSeconds(step * i);
            double y = linearWins ? Math.max(0, full[0] * seconds(origin, t) + full[1]) : lastValue;
            predictions.add(new Prediction(t, y));
        }
        return new Forecast(linearWins ? "linear-regression" : "persistence", train.size(), test.size(),
                linearWins ? linearMae : baselineMae,
                Math.sqrt((linearWins ? sqSum : baselineSq) / n),
                linearMae, baselineMae, step, predictions);
    }

    /** Moindres carrés ordinaires. @return {pente, ordonnée} */
    static double[] fit(List<Point> points, Instant origin) {
        int n = points.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (Point p : points) {
            double x = seconds(origin, p.timestamp());
            sx += x;
            sy += p.powerKw();
            sxx += x * x;
            sxy += x * p.powerKw();
        }
        double denom = n * sxx - sx * sx;
        double slope = denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
        double intercept = (sy - slope * sx) / n;
        return new double[]{slope, intercept};
    }

    static long medianStepSeconds(List<Point> points) {
        long[] steps = new long[points.size() - 1];
        for (int i = 0; i < steps.length; i++) {
            steps[i] = Duration.between(points.get(i).timestamp(), points.get(i + 1).timestamp()).getSeconds();
        }
        java.util.Arrays.sort(steps);
        return Math.max(1, steps[steps.length / 2]);
    }

    private static double seconds(Instant origin, Instant t) {
        return Duration.between(origin, t).toMillis() / 1000.0;
    }
}

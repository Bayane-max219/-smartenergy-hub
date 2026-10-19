package com.smartenergy.analytics.api;

import com.smartenergy.analytics.compute.AnomalyDetector;
import com.smartenergy.analytics.compute.LinearForecaster;
import com.smartenergy.analytics.compute.Point;
import com.smartenergy.analytics.compute.Stats;
import com.smartenergy.analytics.data.MeasurementReader;
import com.smartenergy.analytics.data.SensorThresholds;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/analytics/{sensorId}")
public class AnalyticsController {

    private static final Duration MAX_RANGE = Duration.ofDays(31);

    private final MeasurementReader reader;
    private final SensorThresholds thresholds;
    private final long maxGapSeconds;
    private final Timer forecastTimer;

    public AnalyticsController(MeasurementReader reader, SensorThresholds thresholds, MeterRegistry registry,
                               @Value("${smartenergy.analytics.max-gap-seconds:3600}") long maxGapSeconds) {
        this.reader = reader;
        this.thresholds = thresholds;
        this.maxGapSeconds = maxGapSeconds;
        this.forecastTimer = Timer.builder("smartenergy.forecast.latency")
                .description("Latence de calcul d'une prévision").register(registry);
    }

    public record SummaryResponse(String sensorId, Instant from, Instant to, double thresholdKw,
                                  Stats.Summary summary) {
    }

    public record AnomalyResponse(String sensorId, Instant from, Instant to, double thresholdKw,
                                  double zThreshold, long analysedPoints,
                                  List<AnomalyDetector.Anomaly> anomalies) {
    }

    public record ForecastResponse(String sensorId, Instant from, Instant to, int horizon,
                                   LinearForecaster.Forecast forecast) {
    }

    @Operation(summary = "Indicateurs descriptifs : moyenne, min, max, énergie estimée, dépassements",
            description = "from/to ISO-8601 UTC. Défaut: 24 dernières heures.")
    @GetMapping("/summary")
    public SummaryResponse summary(@PathVariable String sensorId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = start(from, end);
        List<Point> points = load(sensorId, start, end);
        double threshold = thresholds.maxPowerKw(sensorId);
        return new SummaryResponse(sensorId, start, end, threshold,
                Stats.summarize(points, threshold, maxGapSeconds));
    }

    @Operation(summary = "Anomalies détectées (seuil du capteur et score z)")
    @GetMapping("/anomalies")
    public AnomalyResponse anomalies(@PathVariable String sensorId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @RequestParam(defaultValue = "3.0") double zThreshold) {
        Instant end = to != null ? to : Instant.now();
        Instant start = start(from, end);
        List<Point> points = load(sensorId, start, end);
        double threshold = thresholds.maxPowerKw(sensorId);
        return new AnomalyResponse(sensorId, start, end, threshold, zThreshold, points.size(),
                AnomalyDetector.detect(points, threshold, zThreshold));
    }

    @Operation(summary = "Prévision de puissance (baseline : régression linéaire) avec MAE/RMSE",
            description = "Entraînée sur l'historique [from, to] (défaut: 24 h). horizon = nombre de pas à prévoir.")
    @GetMapping("/forecast")
    public ForecastResponse forecast(@PathVariable String sensorId,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @RequestParam(defaultValue = "6") int horizon) {
        if (horizon < 1 || horizon > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "horizon doit être entre 1 et 1000");
        }
        Instant end = to != null ? to : Instant.now();
        Instant start = start(from, end);
        List<Point> points = load(sensorId, start, end);
        LinearForecaster.Forecast f = forecastTimer.record(() -> LinearForecaster.forecast(points, horizon));
        return new ForecastResponse(sensorId, start, end, horizon, f);
    }

    private static Instant start(Instant from, Instant end) {
        Instant start = from != null ? from : end.minus(Duration.ofHours(24));
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from doit précéder to");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plage maximale: 31 jours");
        }
        return start;
    }

    private List<Point> load(String sensorId, Instant from, Instant to) {
        List<Point> points = reader.read(sensorId, from, to);
        if (points.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Aucune mesure pour " + sensorId + " sur la période demandée");
        }
        return points;
    }
}

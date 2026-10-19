package com.smartenergy.storage.api;

import com.smartenergy.storage.cassandra.MeasurementStore;
import com.smartenergy.storage.model.Measurement;
import com.smartenergy.storage.mongo.AggregateStore;
import com.smartenergy.storage.mongo.DailyAggregate;
import com.smartenergy.storage.mongo.Sensor;
import com.smartenergy.storage.mongo.SensorRegistry;
import com.smartenergy.storage.processing.EnergyCalculator;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/sensors")
public class SensorController {

    private final SensorRegistry sensors;
    private final AggregateStore aggregates;
    private final MeasurementStore measurements;

    public SensorController(SensorRegistry sensors, AggregateStore aggregates, MeasurementStore measurements) {
        this.sensors = sensors;
        this.aggregates = aggregates;
        this.measurements = measurements;
    }

    @Operation(summary = "Créer un capteur")
    @PostMapping
    public ResponseEntity<Sensor> create(@Valid @RequestBody SensorRequest request) {
        if (sensors.exists(request.sensorId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Capteur déjà existant: " + request.sensorId());
        }
        Sensor saved = sensors.save(request.toSensor(request.sensorId()));
        return ResponseEntity.created(URI.create("/api/sensors/" + saved.sensorId())).body(saved);
    }

    @Operation(summary = "Lister les capteurs")
    @GetMapping
    public List<Sensor> list() {
        return sensors.findAll();
    }

    @Operation(summary = "Afficher un capteur")
    @GetMapping("/{id}")
    public Sensor get(@PathVariable String id) {
        return sensors.find(id).orElseThrow(() -> notFound(id));
    }

    @Operation(summary = "Modifier un capteur")
    @PutMapping("/{id}")
    public Sensor update(@PathVariable String id, @Valid @RequestBody SensorRequest request) {
        if (!id.equals(request.sensorId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sensorId du corps différent de l'URL");
        }
        if (!sensors.exists(id)) {
            throw notFound(id);
        }
        return sensors.save(request.toSensor(id));
    }

    @Operation(summary = "Résumé quotidien d'un capteur (agrégat MongoDB)",
            description = "Moyenne, min, max, nombre de mesures et de dépassements (agrégat MongoDB) et énergie estimée "
                    + "(calculée sur la partition Cassandra du jour) pour un jour UTC (défaut: aujourd'hui).")
    @GetMapping("/{id}/daily-summary")
    public DailySummary dailySummary(@PathVariable String id,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(ZoneOffset.UTC);
        DailyAggregate a = aggregates.find(id, day).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune donnée pour " + id + " le " + day));
        Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Measurement> points = measurements.findBetween(id, start, start.plusSeconds(86_399), 100_000);
        return new DailySummary(a.sensorId(), a.date(), a.count(), a.avgPowerKw(),
                a.minPowerKw(), a.maxPowerKw(), a.anomalyCount(), EnergyCalculator.energyKwh(points, 3600));
    }

    public record DailySummary(String sensorId, String date, long count, double avgPowerKw,
                               double minPowerKw, double maxPowerKw, long anomalyCount,
                               double energyKwh) {
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Capteur introuvable: " + id);
    }
}

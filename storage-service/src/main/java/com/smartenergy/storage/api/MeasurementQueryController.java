package com.smartenergy.storage.api;

import com.smartenergy.storage.cassandra.MeasurementStore;
import com.smartenergy.storage.model.Measurement;
import com.smartenergy.storage.processing.LatestCache;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/measurements")
public class MeasurementQueryController {

    static final Duration MAX_RANGE = Duration.ofDays(31);
    static final int MAX_LIMIT = 10_000;

    private final MeasurementStore store;
    private final LatestCache cache;

    public MeasurementQueryController(MeasurementStore store, LatestCache cache) {
        this.store = store;
        this.cache = cache;
    }

    @Operation(summary = "Historique d'un capteur entre deux dates (plus récent d'abord)",
            description = "from/to au format ISO-8601 UTC (ex: 2026-09-30T00:00:00Z). "
                    + "Défaut: les 24 dernières heures. Plage max 31 jours.")
    @GetMapping("/{sensorId}")
    public List<Measurement> history(@PathVariable String sensorId,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                     @RequestParam(required = false)
                                     @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                     @RequestParam(defaultValue = "1000") int limit) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofHours(24));
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from doit précéder to");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plage maximale: 31 jours");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit doit être entre 1 et " + MAX_LIMIT);
        }
        return store.findBetween(sensorId, start, end, limit);
    }

    @Operation(summary = "Dernière mesure connue (cache Redis, repli sur Cassandra)")
    @GetMapping("/{sensorId}/latest")
    public Measurement latest(@PathVariable String sensorId) {
        return cache.get(sensorId)
                .or(() -> store.findLatest(sensorId, 7))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Aucune mesure pour " + sensorId));
    }
}

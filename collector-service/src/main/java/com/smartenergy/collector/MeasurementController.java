package com.smartenergy.collector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/measurements")
public class MeasurementController {

    private final MeasurementPublisher publisher;
    private final Counter received;

    public MeasurementController(MeasurementPublisher publisher, MeterRegistry registry) {
        this.publisher = publisher;
        this.received = Counter.builder("smartenergy.measurements.received")
                .description("Mesures valides reçues").register(registry);
    }

    @Operation(summary = "Recevoir une mesure",
            description = "Valide la mesure puis la publie dans Redis Streams. "
                    + "202 = acceptée pour traitement (pas encore forcément persistée).")
    @ApiResponse(responseCode = "202", description = "Mesure acceptée")
    @ApiResponse(responseCode = "400", description = "Mesure invalide")
    @ApiResponse(responseCode = "503", description = "Redis indisponible")
    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody EnergyMeasurementRequest request) {
        publisher.publish(request);
        received.increment();
        return ResponseEntity.accepted().build();
    }
}

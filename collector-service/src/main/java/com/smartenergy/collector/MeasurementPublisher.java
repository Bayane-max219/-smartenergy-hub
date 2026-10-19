package com.smartenergy.collector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Publie chaque mesure validée dans un Redis Stream. */
@Service
public class MeasurementPublisher {

    private static final Logger log = LoggerFactory.getLogger(MeasurementPublisher.class);

    private final StringRedisTemplate redis;
    private final String streamKey;
    private final Counter published;
    private final Timer publishTimer;

    public MeasurementPublisher(StringRedisTemplate redis,
                                @Value("${smartenergy.stream.key:energy-measurements}") String streamKey,
                                MeterRegistry registry) {
        this.redis = redis;
        this.streamKey = streamKey;
        this.published = Counter.builder("smartenergy.measurements.published")
                .description("Mesures publiées dans Redis Streams").register(registry);
        this.publishTimer = Timer.builder("smartenergy.measurements.publish.time")
                .description("Temps de publication dans Redis").register(registry);
        registry.gauge("smartenergy.stream.length", this, MeasurementPublisher::streamLength);
    }

    public RecordId publish(EnergyMeasurementRequest r) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("sensorId", r.sensorId());
        fields.put("timestamp", r.timestamp().toString());
        fields.put("powerKw", String.valueOf(r.powerKw()));
        fields.put("voltage", String.valueOf(r.voltage()));
        if (r.temperature() != null) {
            fields.put("temperature", String.valueOf(r.temperature()));
        }
        RecordId id = publishTimer.record(() ->
                redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(streamKey)));
        published.increment();
        log.info("Mesure publiée sensorId={} redisId={}", r.sensorId(), id);
        return id;
    }

    /** Backlog du flux (messages non encore supprimés par le Storage Service). */
    double streamLength() {
        try {
            Long size = redis.opsForStream().size(streamKey);
            return size == null ? 0 : size;
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}

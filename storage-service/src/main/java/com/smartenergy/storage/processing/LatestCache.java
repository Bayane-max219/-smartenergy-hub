package com.smartenergy.storage.processing;

import com.smartenergy.storage.model.Measurement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Cache Redis (hash "latest:{sensorId}") de la dernière mesure de chaque capteur. */
@Component
public class LatestCache {

    private static final Logger log = LoggerFactory.getLogger(LatestCache.class);
    private static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate redis;

    public LatestCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Best effort : une panne du cache ne doit jamais faire échouer la persistance. */
    public void update(Measurement m) {
        try {
            String key = key(m.sensorId());
            Object current = redis.opsForHash().get(key, "timestamp");
            if (current != null && Instant.parse(current.toString()).isAfter(m.timestamp())) {
                return;
            }
            Map<String, String> fields = new HashMap<>();
            fields.put("sensorId", m.sensorId());
            fields.put("timestamp", m.timestamp().toString());
            fields.put("powerKw", String.valueOf(m.powerKw()));
            fields.put("voltage", String.valueOf(m.voltage()));
            if (m.temperature() != null) {
                fields.put("temperature", String.valueOf(m.temperature()));
            }
            redis.opsForHash().putAll(key, fields);
            redis.expire(key, TTL);
        } catch (Exception e) {
            log.warn("Mise à jour du cache impossible pour {}: {}", m.sensorId(), e.toString());
        }
    }

    public Optional<Measurement> get(String sensorId) {
        try {
            Map<Object, Object> f = redis.opsForHash().entries(key(sensorId));
            if (f.isEmpty()) {
                return Optional.empty();
            }
            Object temp = f.get("temperature");
            return Optional.of(new Measurement(sensorId, Instant.parse(f.get("timestamp").toString()),
                    Double.parseDouble(f.get("powerKw").toString()),
                    Double.parseDouble(f.get("voltage").toString()),
                    temp == null ? null : Double.parseDouble(temp.toString())));
        } catch (Exception e) {
            log.warn("Lecture du cache impossible pour {}: {}", sensorId, e.toString());
            return Optional.empty();
        }
    }

    private static String key(String sensorId) {
        return "latest:" + sensorId;
    }
}

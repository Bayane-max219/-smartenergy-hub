package com.smartenergy.storage;

import com.smartenergy.storage.cassandra.MeasurementStore;
import com.smartenergy.storage.model.Measurement;
import com.smartenergy.storage.mongo.AggregateStore;
import com.smartenergy.storage.stream.MeasurementStreamConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis → Storage → Cassandra / MongoDB → API, avec de vraies bases (Testcontainers).
 * Ignoré automatiquement si Docker n'est pas disponible.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class StoragePipelineIntegrationTest {

    private static final String STREAM = "energy-measurements";
    private static final String DLQ = "energy-measurements-dlq";
    private static final Instant T0 = Instant.parse("2026-09-30T08:15:00Z");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Container
    @ServiceConnection
    static CassandraContainer<?> cassandra = new CassandraContainer<>("cassandra:5.0")
            .withEnv("MAX_HEAP_SIZE", "512M")
            .withEnv("HEAP_NEWSIZE", "128M")
            .withInitScript("init-cassandra.cql");

    @Autowired StringRedisTemplate redisTemplate;
    @Autowired MeasurementStore store;
    @Autowired AggregateStore aggregates;
    @Autowired MeasurementStreamConsumer consumer;
    @Autowired TestRestTemplate rest;

    private void xadd(String stream, String sensorId, Instant ts, String power) {
        Map<String, String> f = new HashMap<>();
        f.put("sensorId", sensorId);
        f.put("timestamp", ts.toString());
        f.put("powerKw", power);
        f.put("voltage", "230.0");
        f.put("temperature", "21.5");
        redisTemplate.opsForStream().add(StreamRecords.string(f).withStreamKey(stream));
    }

    private List<Measurement> history(String sensorId) {
        return store.findBetween(sensorId, T0.minusSeconds(60), T0.plusSeconds(3600), 100);
    }

    private static LocalDate day() {
        return T0.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private void awaitStreamDrained() {
        await().atMost(Duration.ofSeconds(30)).until(() ->
                redisTemplate.opsForStream().size(STREAM) == 0
                        && redisTemplate.opsForStream().pending(STREAM, "storage-group")
                        .getTotalPendingMessages() == 0);
    }

    @Test
    void redisEventIsPersistedInCassandraAndMongoThenAcknowledgedAndReadableViaApi() {
        xadd(STREAM, "IT-FLOW", T0, "12.5");   // 12,5 kW > seuil par défaut 8 kW

        await().atMost(Duration.ofSeconds(30)).until(() -> history("IT-FLOW").size() == 1);
        awaitStreamDrained();

        // Storage -> Cassandra
        assertEquals(12.5, history("IT-FLOW").get(0).powerKw());
        // Storage -> MongoDB : capteur auto-enregistré + agrégat avec anomalie
        var aggregate = aggregates.find("IT-FLOW", day()).orElseThrow();
        assertEquals(1, aggregate.count());
        assertEquals(1, aggregate.anomalyCount());
        // lecture via l'API REST
        ResponseEntity<String> latest = rest.getForEntity("/api/measurements/IT-FLOW/latest", String.class);
        assertEquals(200, latest.getStatusCode().value());
        assertTrue(latest.getBody().contains("\"powerKw\":12.5"));
        assertEquals(200, rest.getForEntity("/api/sensors/IT-FLOW", String.class).getStatusCode().value());
        assertEquals(200, rest.getForEntity("/api/sensors/IT-FLOW/daily-summary?date=" + day(), String.class)
                .getStatusCode().value());
    }

    @Test
    void duplicateEventIsStoredOnlyOnceAndDoesNotDistortAggregates() {
        xadd(STREAM, "IT-DUP", T0, "3.0");
        xadd(STREAM, "IT-DUP", T0, "3.0");
        xadd(STREAM, "IT-DUP", T0.plusSeconds(10), "5.0");

        await().atMost(Duration.ofSeconds(30)).until(() -> history("IT-DUP").size() == 2);
        awaitStreamDrained();

        assertEquals(2, history("IT-DUP").size());
        assertEquals(2, aggregates.find("IT-DUP", day()).orElseThrow().count());
    }

    @Test
    void unreadableEventGoesToDeadLetterWithoutBlockingTheStream() {
        long dlqBefore = redisTemplate.opsForStream().size(DLQ) == null ? 0 : redisTemplate.opsForStream().size(DLQ);
        xadd(STREAM, "IT-BAD", T0, "pas-un-nombre");
        xadd(STREAM, "IT-AFTER-BAD", T0, "2.0");

        await().atMost(Duration.ofSeconds(30)).until(() -> history("IT-AFTER-BAD").size() == 1);
        awaitStreamDrained();

        assertEquals(dlqBefore + 1, redisTemplate.opsForStream().size(DLQ));
        assertEquals(0, history("IT-BAD").size());
    }

    @Test
    void messagesAddedWhileConsumerIsStoppedAreProcessedAfterRestart() {
        consumer.stop();
        try {
            for (int i = 0; i < 3; i++) {
                xadd(STREAM, "IT-RESTART", T0.plusSeconds(i), "4.0");
            }
            assertEquals(3, redisTemplate.opsForStream().size(STREAM));   // conservés dans Redis
            assertEquals(0, history("IT-RESTART").size());
        } finally {
            consumer.start();
        }

        await().atMost(Duration.ofSeconds(30)).until(() -> history("IT-RESTART").size() == 3);
        awaitStreamDrained();
        assertEquals(3, aggregates.find("IT-RESTART", day()).orElseThrow().count());
    }
}

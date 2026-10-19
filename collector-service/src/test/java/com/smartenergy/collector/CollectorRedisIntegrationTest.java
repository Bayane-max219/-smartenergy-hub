package com.smartenergy.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** API → Redis : un vrai Redis démarré par Testcontainers (ignoré si Docker est absent). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CollectorRedisIntegrationTest {

    private static final String STREAM = "energy-measurements";

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Autowired
    TestRestTemplate rest;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanStream() {
        redisTemplate.delete(STREAM);
    }

    private HttpStatus post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return HttpStatus.valueOf(rest.postForEntity("/api/measurements", new HttpEntity<>(json, headers),
                String.class).getStatusCode().value());
    }

    @Test
    void validMeasurementCreatesOneRedisEventWithAllFields() {
        HttpStatus status = post("""
                {"sensorId":"IT-001","timestamp":"2026-09-30T08:15:00Z",
                 "powerKw":3.72,"voltage":226.4,"temperature":29.1}""");

        assertEquals(HttpStatus.ACCEPTED, status);
        List<MapRecord<String, Object, Object>> events = redisTemplate.opsForStream().range(STREAM,
                org.springframework.data.domain.Range.unbounded());
        assertEquals(1, events.size());
        assertEquals("IT-001", events.get(0).getValue().get("sensorId"));
        assertEquals("2026-09-30T08:15:00Z", events.get(0).getValue().get("timestamp"));
        assertEquals("3.72", events.get(0).getValue().get("powerKw"));
    }

    @Test
    void invalidMeasurementCreatesNoRedisEvent() {
        HttpStatus status = post("""
                {"timestamp":"2026-09-30T08:15:00Z","powerKw":-1,"voltage":226.4}""");

        assertEquals(HttpStatus.BAD_REQUEST, status);
        assertEquals(0L, redisTemplate.opsForStream().size(STREAM));
    }
}

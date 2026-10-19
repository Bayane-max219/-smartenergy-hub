package com.smartenergy.storage.stream;

import com.smartenergy.storage.model.InvalidMeasurementException;
import com.smartenergy.storage.model.Measurement;
import com.smartenergy.storage.model.MeasurementParser;
import com.smartenergy.storage.processing.MeasurementProcessor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consomme le Redis Stream via un consumer group.
 *
 * <p>Un message n'est acquitté (XACK) qu'après persistance réussie. Si la persistance échoue
 * (base indisponible), il reste « pending » et est relu — d'abord — à la prochaine itération
 * ou au redémarrage du service : aucune mesure n'est perdue.
 */
@Component
public class MeasurementStreamConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MeasurementStreamConsumer.class);

    private final StringRedisTemplate redis;
    private final MeasurementProcessor processor;
    private final String stream;
    private final String dlq;
    private final String group;
    private final Consumer consumer;
    private final int batchSize;
    private final Duration block;
    private final long backoffMs;
    private final boolean deleteAfterAck;
    private final Counter invalid;
    private final Counter persistenceErrors;

    private volatile boolean running;
    private Thread thread;

    public MeasurementStreamConsumer(StringRedisTemplate redis, MeasurementProcessor processor, MeterRegistry registry,
                                     @Value("${smartenergy.stream.key}") String stream,
                                     @Value("${smartenergy.stream.dlq-key}") String dlq,
                                     @Value("${smartenergy.stream.group}") String group,
                                     @Value("${smartenergy.stream.consumer}") String consumerName,
                                     @Value("${smartenergy.stream.batch-size}") int batchSize,
                                     @Value("${smartenergy.stream.block-ms}") long blockMs,
                                     @Value("${smartenergy.stream.retry-backoff-ms}") long backoffMs,
                                     @Value("${smartenergy.stream.delete-after-ack}") boolean deleteAfterAck) {
        this.redis = redis;
        this.processor = processor;
        this.stream = stream;
        this.dlq = dlq;
        this.group = group;
        this.consumer = Consumer.from(group, consumerName);
        this.batchSize = batchSize;
        this.block = Duration.ofMillis(blockMs);
        this.backoffMs = backoffMs;
        this.deleteAfterAck = deleteAfterAck;
        this.invalid = Counter.builder("smartenergy.measurements.invalid")
                .description("Messages Redis illisibles envoyés en dead-letter").register(registry);
        this.persistenceErrors = Counter.builder("smartenergy.persistence.errors")
                .description("Erreurs de persistance (message laissé en attente)").register(registry);
        registry.gauge("smartenergy.stream.pending", this, MeasurementStreamConsumer::pendingCount);
    }

    @Override
    public void start() {
        running = true;
        ensureGroup();
        thread = new Thread(this::loop, "storage-stream-consumer");
        thread.start();
        log.info("Consumer démarré stream={} group={} consumer={}", stream, group, consumer.getName());
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                poll();
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.error("Erreur du consumer, nouvelle tentative dans {} ms: {}", backoffMs, e.toString());
                if (String.valueOf(e.getMessage()).contains("NOGROUP")) {
                    ensureGroup();
                }
                sleep(backoffMs);
            }
        }
    }

    private void poll() {
        // 1) messages déjà livrés à ce consumer mais jamais acquittés (reprise après crash/panne)
        List<MapRecord<String, Object, Object>> pending = redis.opsForStream().read(consumer,
                StreamReadOptions.empty().count(batchSize), StreamOffset.create(stream, ReadOffset.from("0")));
        if (pending != null && !pending.isEmpty()) {
            if (!handleAll(pending)) {
                sleep(backoffMs);
            }
            return;
        }
        // 2) nouveaux messages (bloquant quelques secondes)
        List<MapRecord<String, Object, Object>> fresh = redis.opsForStream().read(consumer,
                StreamReadOptions.empty().count(batchSize).block(block),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (fresh != null && !fresh.isEmpty()) {
            handleAll(fresh);
        }
    }

    /** @return false si la persistance a échoué (le reste du lot reste en attente). */
    private boolean handleAll(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> record : records) {
            try {
                Measurement m = MeasurementParser.parse(record.getValue());
                MeasurementProcessor.Outcome outcome = processor.process(m);
                log.debug("Message {} -> {} ({})", record.getId(), outcome, m.sensorId());
                ack(record);
            } catch (InvalidMeasurementException e) {
                invalid.increment();
                log.warn("Message {} invalide ({}), envoyé en dead-letter", record.getId(), e.getMessage());
                deadLetter(record, e.getMessage());
                ack(record);
            } catch (Exception e) {
                persistenceErrors.increment();
                log.error("Persistance impossible pour {} (reste en attente): {}", record.getId(), e.toString());
                return false;
            }
        }
        return true;
    }

    private void ack(MapRecord<String, Object, Object> record) {
        redis.opsForStream().acknowledge(stream, group, record.getId());
        if (deleteAfterAck) {
            redis.opsForStream().delete(stream, record.getId());
        }
    }

    private void deadLetter(MapRecord<String, Object, Object> record, String reason) {
        Map<String, String> fields = new LinkedHashMap<>();
        record.getValue().forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        fields.put("_originalId", record.getId().getValue());
        fields.put("_error", reason);
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(dlq));
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            log.info("Consumer group {} créé sur {}", group, stream);
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("BUSYGROUP")
                    || String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                log.debug("Consumer group {} déjà existant", group);
            } else {
                log.warn("Création du consumer group impossible: {}", e.toString());
            }
        }
    }

    double pendingCount() {
        try {
            PendingMessagesSummary summary = redis.opsForStream().pending(stream, group);
            return summary == null ? 0 : summary.getTotalPendingMessages();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.smartenergy.analytics.data;

import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyClass;
import org.springframework.data.cassandra.core.mapping.PrimaryKeyColumn;
import org.springframework.data.cassandra.core.mapping.Table;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Ligne de la table measurements_by_sensor_day : partition = (sensor_id, day). */
@Table("measurements_by_sensor_day")
public class MeasurementEntity {

    @PrimaryKeyClass
    public static class Key implements Serializable {

        @PrimaryKeyColumn(name = "sensor_id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
        private String sensorId;

        @PrimaryKeyColumn(name = "day", ordinal = 1, type = PrimaryKeyType.PARTITIONED)
        private LocalDate day;

        @PrimaryKeyColumn(name = "measured_at", ordinal = 2, type = PrimaryKeyType.CLUSTERED,
                ordering = Ordering.DESCENDING)
        private Instant measuredAt;

        public Key() {
        }

        public Key(String sensorId, LocalDate day, Instant measuredAt) {
            this.sensorId = sensorId;
            this.day = day;
            this.measuredAt = measuredAt;
        }

        public String getSensorId() {
            return sensorId;
        }

        public LocalDate getDay() {
            return day;
        }

        public Instant getMeasuredAt() {
            return measuredAt;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(sensorId, k.sensorId)
                    && Objects.equals(day, k.day) && Objects.equals(measuredAt, k.measuredAt);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sensorId, day, measuredAt);
        }
    }

    @PrimaryKey
    private Key key;

    @Column("power_kw")
    private Double powerKw;

    private Double voltage;

    private Double temperature;

    public MeasurementEntity() {
    }

    public MeasurementEntity(Key key, Double powerKw, Double voltage, Double temperature) {
        this.key = key;
        this.powerKw = powerKw;
        this.voltage = voltage;
        this.temperature = temperature;
    }

    public Key getKey() {
        return key;
    }

    public Double getPowerKw() {
        return powerKw;
    }

    public Double getVoltage() {
        return voltage;
    }

    public Double getTemperature() {
        return temperature;
    }
}

package com.smartenergy.analytics.data;

import com.smartenergy.analytics.compute.Point;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.data.cassandra.core.query.Criteria;
import org.springframework.data.cassandra.core.query.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Lecture seule de la table Cassandra measurements_by_sensor_day. */
@Repository
public class MeasurementReader {

    private final CassandraTemplate template;
    private final int maxPoints;

    public MeasurementReader(CassandraTemplate template,
                             @Value("${smartenergy.analytics.max-points:100000}") int maxPoints) {
        this.template = template;
        this.maxPoints = maxPoints;
    }

    /** Points de [from, to] triés par date croissante (une requête par partition journalière). */
    public List<Point> read(String sensorId, Instant from, Instant to) {
        List<Point> points = new ArrayList<>();
        LocalDate lastDay = to.atZone(ZoneOffset.UTC).toLocalDate();
        for (LocalDate day = from.atZone(ZoneOffset.UTC).toLocalDate();
             !day.isAfter(lastDay) && points.size() < maxPoints; day = day.plusDays(1)) {
            Query query = Query.query(
                            Criteria.where("sensor_id").is(sensorId),
                            Criteria.where("day").is(day),
                            Criteria.where("measured_at").gte(from),
                            Criteria.where("measured_at").lte(to))
                    .limit(maxPoints - points.size());
            for (MeasurementEntity e : template.select(query, MeasurementEntity.class)) {
                points.add(new Point(e.getKey().getMeasuredAt(), e.getPowerKw()));
            }
        }
        points.sort(Comparator.comparing(Point::timestamp));
        return points;
    }
}

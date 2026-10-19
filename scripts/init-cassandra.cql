CREATE KEYSPACE IF NOT EXISTS smartenergy
WITH replication = {
  'class': 'SimpleStrategy',
  'replication_factor': 1
};

-- Requête principale : historique d'un capteur entre deux dates.
-- Partition = (capteur, jour) : une partition ne grossit jamais indéfiniment.
-- Clustering DESC : la mesure la plus récente est lue en premier.
CREATE TABLE IF NOT EXISTS smartenergy.measurements_by_sensor_day (
  sensor_id   text,
  day         date,
  measured_at timestamp,
  power_kw    double,
  voltage     double,
  temperature double,
  PRIMARY KEY ((sensor_id, day), measured_at)
) WITH CLUSTERING ORDER BY (measured_at DESC);

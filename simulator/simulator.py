#!/usr/bin/env python3
"""Simulateur de capteurs énergétiques pour SmartEnergy Hub.

Puissance simulée (équation 1 du guide) :  P_t = P_base + A·sin(2π·t / T) + ε_t
Un capteur peut recevoir une anomalie volontaire (puissance × facteur pendant une courte période).
Aucune dépendance externe (bibliothèque standard uniquement).

Exemples :
  python3 simulator.py                                   # 5 capteurs, toutes les 2 s, 120 s
  python3 simulator.py --sensors 3 --duration 30 --anomaly-sensor SENSOR-002 --anomaly-after 10
  python3 simulator.py --duration 0                      # tourne jusqu'à Ctrl+C
Chaque option a une variable d'environnement équivalente (voir les valeurs par défaut).
"""
import argparse
import json
import math
import os
import random
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone


def env(name, default, cast=str):
    return cast(os.environ.get(name, default))


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default=env("COLLECTOR_URL", "http://localhost:8081/api/measurements"))
    p.add_argument("--sensors", type=int, default=env("SENSORS", 5, int), help="nombre de capteurs")
    p.add_argument("--interval", type=float, default=env("INTERVAL", 2.0, float), help="secondes entre deux mesures")
    p.add_argument("--duration", type=float, default=env("DURATION", 120.0, float), help="durée en s (0 = infini)")
    p.add_argument("--base-power", type=float, default=env("BASE_POWER", 3.0, float), help="P_base en kW")
    p.add_argument("--amplitude", type=float, default=env("AMPLITUDE", 1.0, float), help="A en kW")
    p.add_argument("--period", type=float, default=env("PERIOD", 60.0, float), help="T en secondes")
    p.add_argument("--noise", type=float, default=env("NOISE", 0.15, float), help="écart-type du bruit en kW")
    p.add_argument("--anomaly-sensor", default=env("ANOMALY_SENSOR", "SENSOR-001"),
                   help="capteur recevant l'anomalie ('none' pour désactiver)")
    p.add_argument("--anomaly-after", type=float, default=env("ANOMALY_AFTER", 30.0, float),
                   help="secondes avant le début de l'anomalie")
    p.add_argument("--anomaly-duration", type=float, default=env("ANOMALY_DURATION", 6.0, float))
    p.add_argument("--anomaly-factor", type=float, default=env("ANOMALY_FACTOR", 3.0, float))
    p.add_argument("--seed", type=int, default=env("SEED", 42, int))
    p.add_argument("--dry-run", action="store_true", help="affiche les mesures sans les envoyer")
    return p.parse_args()


def power(args, rng, index, elapsed):
    p_base = args.base_power + 0.3 * index                       # chaque capteur a sa conso moyenne
    phase = index * math.pi / 5
    value = p_base + args.amplitude * math.sin(2 * math.pi * elapsed / args.period + phase)
    value += rng.gauss(0, args.noise)
    return max(0.0, value)


def in_anomaly(args, sensor_id, elapsed):
    return (sensor_id == args.anomaly_sensor
            and args.anomaly_after <= elapsed < args.anomaly_after + args.anomaly_duration)


def post(url, payload):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except (urllib.error.URLError, TimeoutError):
        return 0


def main():
    args = parse_args()
    rng = random.Random(args.seed)
    sensors = [f"SENSOR-{i + 1:03d}" for i in range(args.sensors)]
    counts = {"sent": 0, "accepted": 0, "rejected": 0, "unreachable": 0, "anomalies": 0}
    start = time.monotonic()
    print(f"Simulateur: {len(sensors)} capteurs -> {args.url} (toutes les {args.interval}s, "
          f"durée {'infinie' if args.duration == 0 else str(args.duration) + 's'})", flush=True)
    try:
        while args.duration == 0 or time.monotonic() - start < args.duration:
            tick = time.monotonic()
            elapsed = tick - start
            for i, sensor_id in enumerate(sensors):
                p = power(args, rng, i, elapsed)
                if in_anomaly(args, sensor_id, elapsed):
                    p *= args.anomaly_factor
                    counts["anomalies"] += 1
                payload = {
                    "sensorId": sensor_id,
                    "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                    "powerKw": round(p, 3),
                    "voltage": round(rng.gauss(230, 2), 1),
                    "temperature": round(22 + 4 * math.sin(2 * math.pi * elapsed / 600) + rng.gauss(0, 0.3), 1),
                }
                counts["sent"] += 1
                if args.dry_run:
                    print(json.dumps(payload))
                    continue
                status = post(args.url, payload)
                if status == 202:
                    counts["accepted"] += 1
                elif status == 0:
                    counts["unreachable"] += 1
                else:
                    counts["rejected"] += 1
                    print(f"  rejet HTTP {status} pour {sensor_id}", flush=True)
            time.sleep(max(0.0, args.interval - (time.monotonic() - tick)))
    except KeyboardInterrupt:
        print("\nInterrompu.")
    print("Bilan:", json.dumps(counts), flush=True)


if __name__ == "__main__":
    main()

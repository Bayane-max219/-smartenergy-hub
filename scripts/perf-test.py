#!/usr/bin/env python3
"""Mesures de performance de SmartEnergy Hub (pas un benchmark scientifique : ordres de grandeur).

  perf-test.py seed [--sensors 2 --days 3 --step 2]   charge des mesures directement dans Cassandra (cqlsh COPY)
  perf-test.py read                                    latence des lectures (historique, analytics) sur ce jeu
  perf-test.py load [--threads 1 4 16 --requests 3000] débit du Collector puis temps de vidage du flux par le Storage

La plateforme doit tourner (docker compose up -d). Bibliothèque standard uniquement ; docker compose est
utilisé pour cqlsh et redis-cli (les bases ne sont pas exposées sur l'hôte).
"""
import argparse
import http.client
import json
import os
import statistics
import subprocess
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COLLECTOR = os.environ.get("COLLECTOR_URL", "http://localhost:8081")
STORAGE = os.environ.get("STORAGE_URL", "http://localhost:8082")
ANALYTICS = os.environ.get("ANALYTICS_URL", "http://localhost:8083")


def compose(*args, **kw):
    return subprocess.run(["docker", "compose", *args], cwd=ROOT, capture_output=True, text=True, **kw)


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def percentile(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


# ---------------------------------------------------------------- seed
def seed(args):
    end = datetime.now(timezone.utc).replace(microsecond=0)
    start = end - timedelta(days=args.days)
    rows = 0
    with tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False) as f:
        for s in range(args.sensors):
            t = start
            while t < end:
                p = 3 + 0.3 * s + (t.timestamp() % 3600) / 3600  # valeurs plausibles et variées
                f.write(f"PERF-{s + 1},{t:%Y-%m-%d},{t:%Y-%m-%d %H:%M:%S}+0000,{p:.3f},230.0,22.0\n")
                t += timedelta(seconds=args.step)
                rows += 1
        path = f.name
    print(f"{rows} lignes générées ({args.sensors} capteurs x {args.days} j, 1 mesure / {args.step} s)")
    compose("cp", path, "cassandra:/tmp/perf.csv")
    t0 = time.time()
    cql = ("COPY smartenergy.measurements_by_sensor_day (sensor_id, day, measured_at, power_kw, voltage, temperature) "
           "FROM '/tmp/perf.csv' WITH NUMPROCESSES=2 AND CHUNKSIZE=1000")
    r = compose("exec", "-T", "cassandra", "cqlsh", "-e", cql)
    dt = time.time() - t0
    print(r.stdout.strip().splitlines()[-1] if r.stdout.strip() else r.stderr[-300:])
    print(f"Chargement Cassandra : {rows / dt:,.0f} lignes/s ({dt:.1f} s)")
    os.unlink(path)


# ---------------------------------------------------------------- read
def timed_get(conn, path):
    t0 = time.perf_counter()
    conn.request("GET", path)
    resp = conn.getresponse()
    body = resp.read()
    return (time.perf_counter() - t0) * 1000, resp.status, len(body)


def read(args):
    now = datetime.now(timezone.utc)
    scenarios = [
        ("Storage  /latest (repli Cassandra)", STORAGE, "/api/measurements/PERF-1/latest"),
        ("Storage  historique 1 h (~1 800 pts)", STORAGE,
         f"/api/measurements/PERF-1?from={iso(now - timedelta(hours=1))}&to={iso(now)}&limit=10000"),
        ("Storage  historique 1 jour (limit 10 000)", STORAGE,
         f"/api/measurements/PERF-1?from={iso(now - timedelta(days=1))}&to={iso(now)}&limit=10000"),
        ("Storage  historique 3 jours (limit 10 000)", STORAGE,
         f"/api/measurements/PERF-1?from={iso(now - timedelta(days=3))}&to={iso(now)}&limit=10000"),
        ("Analytics summary 1 jour (~43 200 pts)", ANALYTICS,
         f"/api/analytics/PERF-1/summary?from={iso(now - timedelta(days=1))}&to={iso(now)}"),
        ("Analytics summary 2 jours (~86 400 pts)", ANALYTICS,
         f"/api/analytics/PERF-1/summary?from={iso(now - timedelta(days=2))}&to={iso(now)}"),
        ("Analytics anomalies 1 jour", ANALYTICS,
         f"/api/analytics/PERF-1/anomalies?from={iso(now - timedelta(days=1))}&to={iso(now)}"),
        ("Analytics forecast 1 jour", ANALYTICS,
         f"/api/analytics/PERF-1/forecast?from={iso(now - timedelta(days=1))}&to={iso(now)}&horizon=6"),
    ]
    print(f"{'Scénario':<46}{'n':>4}{'p50 ms':>9}{'p95 ms':>9}{'max ms':>9}{'octets':>10}  HTTP")
    for name, base, path in scenarios:
        u = urlparse(base)
        conn = http.client.HTTPConnection(u.hostname, u.port, timeout=60)
        for _ in range(3):  # échauffement (JIT, connexions)
            timed_get(conn, path)
        lat, status, size = [], 0, 0
        for _ in range(args.n):
            ms, status, size = timed_get(conn, path)
            lat.append(ms)
        print(f"{name:<46}{args.n:>4}{percentile(lat, 50):>9.1f}{percentile(lat, 95):>9.1f}{max(lat):>9.1f}{size:>10}  {status}")


# ---------------------------------------------------------------- load
def redis_cli(*cmd):
    return compose("exec", "-T", "redis", "redis-cli", *cmd).stdout.strip()


def drained():
    xlen = int(redis_cli("XLEN", "energy-measurements") or 0)
    pend = redis_cli("XPENDING", "energy-measurements", "storage-group").splitlines()[0]
    return xlen == 0 and int(pend) == 0


def metric(base, name):
    conn = http.client.HTTPConnection(urlparse(base).hostname, urlparse(base).port, timeout=10)
    conn.request("GET", "/actuator/prometheus")
    for line in conn.getresponse().read().decode().splitlines():
        if line.startswith(name):
            return float(line.split()[-1])
    return 0.0


def load(args):
    counter = {"n": 0}
    lock = threading.Lock()
    base_ts = datetime.now(timezone.utc) - timedelta(hours=2)
    print(f"{'threads':>7}{'req':>7}{'req/s':>9}{'p50 ms':>9}{'p95 ms':>9}{'p99 ms':>9}{'err':>5}"
          f"{'vidage s':>10}{'bout-en-bout/s':>16}")
    for threads in args.threads:
        stored0 = metric(STORAGE, "smartenergy_measurements_stored_total")
        latencies, errors = [], [0]

        def worker(total):
            u = urlparse(COLLECTOR)
            conn = http.client.HTTPConnection(u.hostname, u.port, timeout=30)
            while True:
                with lock:
                    if counter["n"] >= total:
                        return
                    i = counter["n"]
                    counter["n"] += 1
                payload = json.dumps({
                    "sensorId": f"LOAD-{i % 10}",
                    "timestamp": iso(base_ts + timedelta(milliseconds=i + 1_000_000 * threads)),
                    "powerKw": 3.0 + (i % 7) * 0.1, "voltage": 230.0, "temperature": 21.0})
                t0 = time.perf_counter()
                try:
                    conn.request("POST", "/api/measurements", payload, {"Content-Type": "application/json"})
                    resp = conn.getresponse()
                    resp.read()
                    ok = resp.status == 202
                except Exception:
                    ok = False
                    conn = http.client.HTTPConnection(u.hostname, u.port, timeout=30)
                dt = (time.perf_counter() - t0) * 1000
                with lock:
                    latencies.append(dt)
                    if not ok:
                        errors[0] += 1

        counter["n"] = 0
        t0 = time.perf_counter()
        ts = [threading.Thread(target=worker, args=(args.requests,)) for _ in range(threads)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        send_s = time.perf_counter() - t0
        while not drained():
            time.sleep(0.3)
        total_s = time.perf_counter() - t0
        time.sleep(1)
        stored = metric(STORAGE, "smartenergy_measurements_stored_total") - stored0
        print(f"{threads:>7}{args.requests:>7}{args.requests / send_s:>9.0f}{percentile(latencies, 50):>9.1f}"
              f"{percentile(latencies, 95):>9.1f}{percentile(latencies, 99):>9.1f}{errors[0]:>5}"
              f"{total_s - send_s:>10.1f}{stored / total_s:>16.0f}   (stockées: {stored:.0f})")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("seed")
    s.add_argument("--sensors", type=int, default=2)
    s.add_argument("--days", type=int, default=3)
    s.add_argument("--step", type=int, default=2, help="secondes entre deux mesures")
    r = sub.add_parser("read")
    r.add_argument("-n", type=int, default=30, help="requêtes mesurées par scénario")
    l = sub.add_parser("load")
    l.add_argument("--threads", type=int, nargs="+", default=[1, 4, 16])
    l.add_argument("--requests", type=int, default=3000)
    args = p.parse_args()
    {"seed": seed, "read": read, "load": load}[args.cmd](args)


if __name__ == "__main__":
    main()

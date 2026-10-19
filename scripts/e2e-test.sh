#!/usr/bin/env bash
# Test de bout en bout contre la plateforme lancée par `docker compose up -d --build`.
# Couvre les scénarios obligatoires du guide (§22.3) :
#   1 mesure valide · 2 mesure invalide · 3 historique · 4 anomalie · 5 arrêt d'un service
#   6 redémarrage · 7 persistance — plus l'idempotence (doublon).
# Usage : scripts/e2e-test.sh [--full-restart]   (--full-restart : docker compose down/up, ~2 min)
set -u
cd "$(dirname "$0")/.."

COLLECTOR=${COLLECTOR_URL:-http://localhost:8081}
STORAGE=${STORAGE_URL:-http://localhost:8082}
ANALYTICS=${ANALYTICS_URL:-http://localhost:8083}
SID="E2E-$(date +%s)"
PASS=0; FAIL=0

BASE=$(date +%s)
# Horodatages déterministes : at <décalage en s> = BASE + décalage (deux appels identiques -> même instant,
# indispensable pour tester l'idempotence).
at() { date -u -d "@$((BASE + $1))" +%Y-%m-%dT%H:%M:%SZ; }
send() {  # send <sensorId> <timestamp> <powerKw> -> code HTTP
  curl -s -o /dev/null -w '%{http_code}' -X POST "$COLLECTOR/api/measurements" \
    -H 'Content-Type: application/json' \
    -d "{\"sensorId\":\"$1\",\"timestamp\":\"$2\",\"powerKw\":$3,\"voltage\":230.0,\"temperature\":21.5}"
}
soon() { date -u -d "+60 seconds" +%Y-%m-%dT%H:%M:%SZ; }        # horodatage UTC dans 60 s (borne haute des requêtes)
history_count() { curl -s "$STORAGE/api/measurements/$SID?from=$(at -3600)&to=$(soon)&limit=1000" | jq 'length' 2>/dev/null || echo 0; }
wait_for() {  # wait_for <timeout s> <commande...> : attend que la commande réussisse
  local t=$1; shift; local i=0
  while ! "$@" >/dev/null 2>&1; do i=$((i+1)); [ $i -ge "$t" ] && return 1; sleep 1; done
}
check() {  # check <description> <attendu> <obtenu>
  if [ "$2" = "$3" ]; then echo "  ✔ $1 (obtenu: $3)"; PASS=$((PASS+1))
  else echo "  ✘ $1 (attendu: $2, obtenu: $3)"; FAIL=$((FAIL+1)); fi
}
stream_len() { docker compose exec -T redis redis-cli XLEN energy-measurements | tr -d '\r'; }
pending() { docker compose exec -T redis redis-cli XPENDING energy-measurements storage-group | head -1 | tr -d '\r'; }
count_ge() { [ "$(history_count)" -ge "$1" ]; }

echo "== Capteur de test: $SID"

echo "[1] Mesure valide"
check "POST mesure valide -> 202" 202 "$(send "$SID" "$(at -130)" 3.5)"
wait_for 20 curl -sf "$STORAGE/api/measurements/$SID/latest"
check "mesure retrouvée via /latest" "$SID" "$(curl -s "$STORAGE/api/measurements/$SID/latest" | jq -r .sensorId)"

echo "[2] Mesure invalide"
check "sans sensorId -> 400" 400 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$COLLECTOR/api/measurements" -H 'Content-Type: application/json' -d "{\"timestamp\":\"$(at 0)\",\"powerKw\":1,\"voltage\":230}")"
check "puissance négative -> 400" 400 "$(send "$SID" "$(at 0)" -4)"
check "JSON cassé -> 400" 400 "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$COLLECTOR/api/measurements" -H 'Content-Type: application/json' -d '{oups')"

echo "[3] Historique ordonné"
for i in $(seq 1 12); do send "$SID" "$(at $((-130 + i*10)))" "$(echo "3 + $i * 0.1" | bc -l)" >/dev/null; done
wait_for 30 count_ge 13
check "13 mesures dans l'historique" 13 "$(history_count)"
check "ordre décroissant (plus récent d'abord)" true "$(curl -s "$STORAGE/api/measurements/$SID?from=$(at -3600)&to=$(soon)" | jq '[.[].timestamp] == ([.[].timestamp] | sort | reverse)')"

echo "[3b] Idempotence : même mesure renvoyée"
BEFORE=$(curl -s "$STORAGE/api/sensors/$SID/daily-summary?date=$(date -u +%F)" | jq .count)
send "$SID" "$(at -130)" 3.5 >/dev/null; sleep 3
check "le compteur MongoDB ne change pas" "$BEFORE" "$(curl -s "$STORAGE/api/sensors/$SID/daily-summary?date=$(date -u +%F)" | jq .count)"
check "toujours 13 lignes Cassandra" 13 "$(history_count)"

echo "[4] Anomalie"
send "$SID" "$(at -5)" 15.0 >/dev/null
wait_for 20 count_ge 14
ANOM=$(curl -s "$ANALYTICS/api/analytics/$SID/anomalies?from=$(at -3600)")
check "pic de 15 kW détecté (seuil)" true "$(echo "$ANOM" | jq '[.anomalies[] | select(.powerKw == 15 and .aboveThreshold)] | length >= 1')"
check "résumé: au moins un dépassement" true "$(curl -s "$ANALYTICS/api/analytics/$SID/summary?from=$(at -3600)" | jq '.summary.thresholdExceedances >= 1')"
check "prévision disponible (HTTP 200)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$ANALYTICS/api/analytics/$SID/forecast?from=$(at -3600)&horizon=3")"

echo "[5] Arrêt du Storage Service : les messages doivent rester dans Redis"
docker compose stop storage-service >/dev/null 2>&1
for i in 1 2 3 4 5; do send "$SID" "$(at $((i - 5)))" 4.0 >/dev/null; done
sleep 1
check "5 messages conservés dans le flux Redis" 5 "$(stream_len)"

echo "[6] Redémarrage du Storage Service : reprise du traitement"
docker compose start storage-service >/dev/null 2>&1
wait_for 60 count_ge 19
check "les 5 mesures sont persistées (19 au total)" 19 "$(history_count)"
sleep 2
check "flux Redis vidé" 0 "$(stream_len)"
check "aucun message en attente" 0 "$(pending)"

if [ "${1:-}" = "--full-restart" ]; then
  echo "[7] Redémarrage complet (docker compose down/up, volumes conservés)"
  docker compose down >/dev/null 2>&1
  docker compose up -d >/dev/null 2>&1
  wait_for 240 curl -sf "$STORAGE/api/health"
  wait_for 60 count_ge 19
fi
echo "[7] Persistance"
check "les 19 mesures sont toujours là" 19 "$(history_count)"
check "le capteur existe toujours dans MongoDB" "$SID" "$(curl -s "$STORAGE/api/sensors/$SID" | jq -r .sensorId)"

echo
echo "Résultat: $PASS réussis, $FAIL échoués"
[ "$FAIL" -eq 0 ]

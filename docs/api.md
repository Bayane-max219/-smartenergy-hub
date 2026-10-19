# API

Documentation interactive (OpenAPI/Swagger UI) : `/swagger-ui.html` sur chaque service ; spécification JSON : `/v3/api-docs`.
Dates : ISO-8601 UTC (`2026-09-30T08:15:00Z`). `from`/`to` : par défaut les 24 dernières heures, plage maximale 31 jours.
Erreurs : JSON `ProblemDetail` (`status`, `detail`, et `errors[]` pour une validation).

## Collector — port 8081

### `POST /api/measurements`

```bash
curl -i -X POST localhost:8081/api/measurements -H 'Content-Type: application/json' -d '{
  "sensorId": "SENSOR-001", "timestamp": "2026-09-30T08:15:00Z",
  "powerKw": 3.72, "voltage": 226.4, "temperature": 29.1 }'
# HTTP/1.1 202
```

| Code | Cas |
|---|---|
| 202 | Mesure acceptée et publiée dans Redis |
| 400 | `sensorId` vide, `powerKw` négatif, tension hors plage, `timestamp` illisible, JSON invalide |
| 503 | Redis indisponible |

Exemple 400 : `{"status":400,"detail":"Mesure invalide","errors":["sensorId: must not be blank"]}`

## Storage — port 8082

| Méthode | Endpoint | Résultat |
|---|---|---|
| POST | `/api/sensors` | Crée un capteur (201 + `Location`, 409 s'il existe, 400 si invalide) |
| GET | `/api/sensors` | Liste des capteurs |
| GET | `/api/sensors/{id}` | Métadonnées (404 si inconnu) |
| PUT | `/api/sensors/{id}` | Remplace un capteur (`sensorId` du corps = id de l'URL) |
| GET | `/api/sensors/{id}/daily-summary?date=2026-09-30` | Agrégat MongoDB du jour (défaut : aujourd'hui) : count, avg, min, max, anomalyCount |
| GET | `/api/measurements/{sensorId}?from&to&limit` | Historique, plus récent d'abord (`limit` 1–10000, défaut 1000) |
| GET | `/api/measurements/{sensorId}/latest` | Dernière mesure (cache Redis, repli Cassandra) |

Exemple de création :

```bash
curl -X POST localhost:8082/api/sensors -H 'Content-Type: application/json' -d '{
  "sensorId": "SENSOR-001", "name": "Compteur salle serveur", "building": "Bâtiment A",
  "floor": 1, "maxPowerKw": 8.0, "active": true, "tags": ["critical", "server-room"] }'
```

## Analytics — port 8083

| Méthode | Endpoint | Résultat |
|---|---|---|
| GET | `/api/analytics/{id}/summary?from&to` | count, moyenne, min, max, énergie estimée (kWh), nombre de dépassements du seuil |
| GET | `/api/analytics/{id}/anomalies?from&to&zThreshold=3` | Mesures > `maxPowerKw` du capteur ou \|z\| > `zThreshold` |
| GET | `/api/analytics/{id}/forecast?from&to&horizon=6` | Prévision (régression linéaire) + MAE, RMSE, `baselineMae` |

- **Énergie** : `E ≈ Σ P_i · Δt_i` (kWh), Δt = écart entre mesures successives ; un trou supérieur à 1 h n'est pas compté.
- **404** si aucune mesure sur la période ; **422** si trop peu de mesures pour la prévision (minimum 10).
- La prévision est une *baseline* : comparez `mae` à `baselineMae` (persistance) avant de lui faire confiance.

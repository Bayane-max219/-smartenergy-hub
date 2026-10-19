# Preuves de tests

Résultats obtenus le 2026-10-19 sur la plateforme lancée par `docker compose up -d --build` (Cassandra 5.0, Redis 7, MongoDB 7, JDK 17).

## Tests unitaires — `mvn test` (JDK 17) : 19 tests, 0 échec

| Module | Classe | Tests | Ce qui est vérifié |
|---|---|---|---|
| collector | `MeasurementControllerTest` | 5 | 202 + publication ; 400 sans `sensorId` (rien publié) ; 400 puissance négative ; 400 timestamp illisible ; 503 si Redis en panne |
| storage | `MeasurementParserTest` | 4 | Parsing d'un événement Redis, température optionnelle, rejets (`sensorId`, nombres, date, négatif) |
| storage | `MeasurementProcessorTest` | 3 | Mesure nouvelle → stockée + agrégat + cache ; dépassement de seuil → anomalie comptée ; **doublon → agrégat et cache intacts** |
| analytics | `ComputeTest` | 7 | Énergie (2 kW × 1 h = 2 kWh), trous ignorés, moyenne/min/max/dépassements, pic détecté (seuil + z-score), série plate sans anomalie, tendance linéaire retrouvée (MAE = 0), refus si < 10 points |

## Tests d'intégration — `scripts/e2e-test.sh` : 18/18 réussis

Chaîne complète sur la vraie plateforme (API → Redis → Storage → Cassandra/MongoDB → API).

| N° | Scénario | Résultat attendu | Résultat obtenu |
|---|---|---|---|
| 1 | Envoi d'une mesure valide | HTTP 202, retrouvée via `/latest` | ✔ 202 ; mesure retrouvée en < 20 s |
| 2 | Mesure sans `sensorId` / puissance négative / JSON cassé | HTTP 400 | ✔ 400 ×3 |
| 3 | Lecture historique (13 mesures) | Liste ordonnée, plus récent d'abord | ✔ 13 mesures, ordre décroissant |
| 3b | Même mesure renvoyée (idempotence) | Aucun changement (Cassandra, agrégat MongoDB) | ✔ 13 lignes, compteur MongoDB inchangé |
| 4 | Injection d'un pic de 15 kW | Anomalie détectée, dépassement compté, prévision disponible | ✔ détecté par seuil ; `thresholdExceedances ≥ 1` ; forecast 200 |
| 5 | Arrêt du Storage Service pendant l'envoi de 5 mesures | Messages conservés dans Redis | ✔ `XLEN energy-measurements` = 5 |
| 6 | Redémarrage du Storage Service | Reprise du traitement | ✔ 19 mesures persistées, flux vidé (XLEN = 0), 0 pending |
| 7 | Persistance | Données toujours présentes | ✔ 19 mesures + capteur MongoDB présents |
| 7b | `docker compose down` puis `up -d` (`--full-restart`, volumes conservés) | Données toujours présentes | ✔ 19 mesures retrouvées (exécution précédente du script) |

## Test du simulateur (§10.2 du guide)

5 capteurs, 1 mesure / 2 s, 120 s → **300 requêtes envoyées, 300 acceptées (202), 0 rejet**.
Compteurs Micrometer sur la même période : Collector `received` +300, Storage `stored` +300 → aucune perte, aucun doublon.
Anomalie injectée sur `SENSOR-001` (puissance × 3 pendant 6 s).

## Autres vérifications manuelles

| Vérification | Résultat |
|---|---|
| `POST /api/sensors` (201), doublon (409), `PUT` avec id différent (400), capteur inconnu (404) | ✔ |
| `from=hier` (400), plage > 31 jours (400) | ✔ |
| Prévision avec 1 seule mesure | ✔ 422 « Au moins 10 mesures nécessaires » |
| Message Redis illisible injecté (`timestamp=pas-une-date`) | ✔ envoyé dans `energy-measurements-dlq`, compteur `invalid` = 1, flux non bloqué |
| Swagger : `/v3/api-docs` sur les 3 services | ✔ 200 |
| Métriques Prometheus `smartenergy_*` | ✔ reçues, rejetées, stockées, doublons, pending, longueur du flux |

## Constats utiles pour le rapport

- Prévision linéaire sur 30 s de sinusoïde : MAE 0,64 kW contre 0,29 kW pour la persistance → la baseline est **moins bonne** qu'une prédiction naïve ; il faut un modèle capable de saisonnalité (LSTM, Holt-Winters) et une fenêtre plus longue.
- z-score : avec 5 points anormaux sur 30, le pic gonfle σ (z ≈ 2,3 < 3) et n'est pas signalé par le z-score, mais l'est par le seuil du capteur (effet de masque).

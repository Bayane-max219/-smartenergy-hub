# SmartEnergy Hub

Plateforme Big Data pour la supervision énergétique distribuée — Projet Master 1 MSI (Spring Boot, MongoDB, Redis, Cassandra, Docker).

```
Simulateur ──HTTP──▶ Collector ──▶ Redis Streams ──▶ Storage ──▶ Cassandra (séries temporelles)
                     (valide)      (tampon)          (consumer)└▶ MongoDB   (capteurs, agrégats)
                                                          Analytics ◀── lit Cassandra + MongoDB
```

| Service | Port hôte | Rôle | Swagger |
|---|---|---|---|
| collector-service | 8081 | Reçoit/valide les mesures, les publie dans Redis Streams | http://localhost:8081/swagger-ui.html |
| storage-service | 8082 | Consomme le flux (consumer group `storage-group`), persiste, expose capteurs + historique | http://localhost:8082/swagger-ui.html |
| analytics-service | 8083 | Moyenne/min/max/énergie, anomalies, prévision | http://localhost:8083/swagger-ui.html |
| dashboard | 3000 | Tableau de bord web (page statique + relais nginx vers les 3 services) | — |

## Prérequis

- Docker + Docker Compose v2 (suffit pour lancer la plateforme)
- Pour compiler/tester hors Docker : **JDK 17** (JDK complet, pas seulement un JRE) et Maven 3.8+
- `curl`, `jq`, `bc` pour `scripts/e2e-test.sh` ; Python 3 pour le simulateur

## Démarrage (commande unique)

```bash
docker compose up -d --build
```

Première fois : ~3-5 min (téléchargement des images, compilation Maven dans Docker, démarrage de Cassandra ~1 min).
Le schéma Cassandra est créé automatiquement par le conteneur `cassandra-init` (`scripts/init-cassandra.cql`).

```bash
docker compose ps                                  # tout doit être Up / healthy
curl localhost:8081/api/health                     # {"status":"UP",...}
```

### Tableau de bord

Ouvrir **http://localhost:3000** : état de la chaîne (Collector → Redis → Storage → Analytics), indicateurs, courbe de puissance en temps réel avec seuil du capteur, anomalies (points rouges) et prévision, et trois boutons de démonstration (mesure normale, pic, mesure invalide) qui envoient réellement une mesure au Collector. Le bouton **« Lancer la simulation »** envoie des mesures simulées (3 capteurs, anomalie périodique sur SENSOR-001) directement depuis la page : aucun autre outil n'est nécessaire pour la démonstration (`http://localhost:3000/?sim=1` la démarre automatiquement). La période « 24 dernières heures » permet de revoir les données déjà stockées. Page unique sans dépendance externe (`dashboard/index.html`), thème clair/sombre.

### Générer des données

```bash
# Simulateur local (aucune dépendance) : 5 capteurs, 1 mesure / 2 s pendant 2 min, anomalie sur SENSOR-001
python3 simulator/simulator.py
python3 simulator/simulator.py --help              # tous les paramètres (aussi en variables d'environnement)

# ou dans Docker
docker compose --profile sim up simulator
```

### Interroger l'API

```bash
curl localhost:8082/api/sensors
curl localhost:8082/api/measurements/SENSOR-001/latest
curl "localhost:8082/api/measurements/SENSOR-001?from=2026-10-19T08:00:00Z&to=2026-10-19T09:00:00Z"
curl localhost:8082/api/sensors/SENSOR-001/daily-summary
curl localhost:8083/api/analytics/SENSOR-001/summary
curl localhost:8083/api/analytics/SENSOR-001/anomalies
curl "localhost:8083/api/analytics/SENSOR-001/forecast?horizon=6"
```

Détail des endpoints : [docs/api.md](docs/api.md) (ou Swagger UI).
Tous les horodatages sont en **UTC ISO-8601** ; `from`/`to` valent par défaut « les 24 dernières heures ».

### Arrêter / relancer

```bash
docker compose down        # conserve les données (volumes mongo_data, redis_data, cassandra_data)
docker compose up -d
docker compose down -v     # ⚠ supprime aussi les données
```

## Tests

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64   # si le JDK par défaut n'a pas javac
mvn test                          # 22 tests unitaires + 6 tests d'intégration (Testcontainers : vrais Redis, Cassandra,
                                  # MongoDB ; ~1 min ; ignorés si Docker est absent)
scripts/e2e-test.sh               # bout en bout, plateforme lancée (18 vérifications, ~1 min)
scripts/e2e-test.sh --full-restart  # + docker compose down/up et contrôle de la persistance (~3 min)
python3 scripts/perf-test.py seed|read|load   # mesures de performance (voir docs/performance.md)
```

Preuves de tests : [docs/tests.md](docs/tests.md) · Performance : [docs/performance.md](docs/performance.md) · Rapport : [docs/rapport.md](docs/rapport.md).

## Observabilité

Chaque service expose Actuator : `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.
Métriques métier (`smartenergy_*`) : mesures reçues/rejetées/publiées (collector), stockées/doublons/invalides/erreurs de persistance/messages pending (storage), latence de prévision (analytics), longueur du flux Redis.

```bash
curl -s localhost:8081/actuator/prometheus | grep smartenergy
```

## Sécurité (niveau minimal)

- Aucun secret dans le dépôt ; configuration par variables d'environnement (`.env.example`, `.env` ignoré par Git).
- Les bases ne sont **pas** exposées sur l'hôte par défaut ; `docker-compose.dev.yml` les expose sur `127.0.0.1` pour le développement seulement.
- Entrées validées (Jakarta Validation) ; profils Spring : défaut (localhost) et `docker`.
- Les endpoints d'écriture/lecture ne sont **pas authentifiés** (Spring Security/JWT non réalisé, voir limites).

## Organisation du dépôt

```
collector-service/  storage-service/  analytics-service/   # modules Maven Spring Boot (+ Dockerfile)
dashboard/          # tableau de bord web (index.html + nginx)
simulator/          # simulateur de capteurs (Python, stdlib)
scripts/            # init-cassandra.cql, sample-data.json, e2e-test.sh, perf-test.py
docs/               # rapport.md, architecture.md, api.md, tests.md, performance.md
docker-compose.yml  docker-compose.dev.yml  .env.example
models/             # réservé aux modèles IA exportés (vide : voir limites)
```

## Limites connues

- **Prévision** : régression linéaire comparée à la persistance (dernière valeur) sur un jeu de test ; le meilleur des deux est retenu (`model`, `mae`, `rmse`, `linearMae`, `baselineMae`). Aucun des deux ne capture la saisonnalité ; pas de LSTM/DJL/ONNX.
- **Détection z-score** : σ est calculé sur la période demandée *y compris* les anomalies (effet de masque sur de petits échantillons) ; le seuil par capteur (`maxPowerKw`) reste la détection fiable.
- **Performance** : le goulot est le Storage (~400 mesures/s, un consumer + une écriture conditionnelle par message) ; le flux Redis absorbe l'excédent sans perte.
- **Fiabilité** : livraison *at-least-once* avec persistance idempotente. Fenêtre résiduelle : si le service s'arrête entre l'insertion Cassandra et la mise à jour de l'agrégat MongoDB, l'agrégat du jour peut omettre une mesure (Cassandra reste la source de vérité ; `/api/analytics` recalcule depuis Cassandra).
- Pas d'authentification/JWT, ni Prometheus/Grafana, ni API Gateway, ni Kafka/Kubernetes (extensions de niveau 2 non réalisées).
- Cassandra à un seul nœud, `replication_factor = 1` (développement).

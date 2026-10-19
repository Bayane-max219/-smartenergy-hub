# SmartEnergy Hub — Plateforme Big Data pour la supervision énergétique distribuée

**Rapport technique — Projet de Master 1 MSI : Spring Boot, Big Data et Intelligence Artificielle**
Encadrant : Fabrice RAZAFINDRAIBE — Année 2026
Auteur(s) : *[Nom Prénom — à compléter]* — Dépôt : https://github.com/Bayane-max219/-smartenergy-hub

---

## 1. Introduction et contexte

Les objets connectés produisent en continu des mesures de consommation (puissance, tension, température). Une application classique à base de données unique atteint vite ses limites lorsqu'il faut gérer en même temps un flux continu, des lectures fréquentes, des historiques volumineux, un cache, des analyses et une forte disponibilité.

SmartEnergy Hub répond à ce besoin par une architecture distribuée qui **collecte, stocke, analyse et expose** les données énergétiques, en répartissant le travail entre trois bases NoSQL selon leur point fort (Redis, Cassandra, MongoDB) et quatre services Spring Boot, le tout déployable par une seule commande Docker Compose.

**Périmètre réalisé** : le MVP obligatoire du sujet (niveau 1) en entier, plus une prévision de référence (régression linéaire). Ne sont pas réalisés : modèle LSTM/DJL, JWT, Prometheus/Grafana, API Gateway, Kafka, Kubernetes, interface Web (§11).

## 2. Cahier des charges et besoins fonctionnels

Le système doit répondre aux questions du sujet :

| Question | Réponse dans le système |
|---|---|
| Dernière valeur reçue pour un capteur ? | `GET /api/measurements/{id}/latest` (cache Redis, repli Cassandra) |
| Consommation moyenne entre deux dates ? | `GET /api/analytics/{id}/summary?from&to` |
| Quels capteurs dépassent un seuil ? | Seuil `maxPowerKw` par capteur (MongoDB) ; `/anomalies`, `anomalyCount` du résumé quotidien |
| Consommation prévue pour les prochaines heures ? | `GET /api/analytics/{id}/forecast` (baseline linéaire, avec MAE/RMSE) |
| Quels événements sont anormaux ? | `GET /api/analytics/{id}/anomalies` (seuil et score z) |

Exigences non fonctionnelles : ne perdre aucune mesure acceptée, tolérer l'arrêt d'un service, rester idempotent, être observable, être lancé en une commande, ne contenir aucun secret.

## 3. Architecture générale et justification des choix

```
Simulateur ─HTTP─▶ Collector ─XADD─▶ Redis Stream ─XREADGROUP─▶ Storage ─▶ Cassandra (mesures)
                  (validation)      (tampon)      storage-group           └▶ MongoDB   (capteurs, agrégats)
                                                                                ▲
                                                           Analytics ───────────┘ (lit Cassandra + MongoDB) ─▶ API REST
```

| Choix | Alternatives considérées | Justification |
|---|---|---|
| 3 services Spring Boot séparés (Collector, Storage, Analytics) | Monolithe | Découple la réception (légère, toujours disponible) du stockage (lourd, dépend des bases) et des calculs ; chaque service se teste, se déploie et monte en charge seul. Coût : plus de configuration. |
| Redis Streams entre Collector et Storage | Appel direct aux bases ; Kafka | Tampon durable (`appendonly`), consumer group, accusés de réception et reprise des messages en attente, sans l'infrastructure de Kafka. Kafka serait préférable à très grande échelle. |
| Cassandra pour les mesures | MongoDB, base relationnelle | Écritures massives, lecture ciblée par clé de partition, croissance horizontale. |
| MongoDB pour capteurs et agrégats | Tables relationnelles | Documents à structure évolutive (tags, bâtiment, seuils), agrégats mis à jour atomiquement (`$inc/$min/$max`). |
| Redis comme cache de la dernière mesure | Lecture Cassandra systématique | Lecture en ~1 ms ; Cassandra reste la source de vérité. |
| Collector répond HTTP 202 | 201/200 | La réponse signifie « acceptée pour traitement » ; la persistance est asynchrone. |

Le Storage Service est le seul à écrire dans les bases ; l'API de consultation des capteurs et de l'historique y est donc hébergée. Analytics lit directement Cassandra et MongoDB (choix assumé : une dépendance en lecture seule, sans appel réseau intermédiaire).

## 4. Modélisation des données

**Contrat d'une mesure (JSON, validé par Jakarta Validation)**

| Champ | Contrainte |
|---|---|
| `sensorId` | obligatoire, non vide, ≤ 64 caractères |
| `timestamp` | obligatoire, ISO-8601 **UTC** |
| `powerKw` | obligatoire, ≥ 0 |
| `voltage` | obligatoire, 0 à 1000 V |
| `temperature` | optionnelle, −60 à 150 °C |

**Questions du sujet (§9.3)**
- *Pourquoi UTC ?* Éviter toute ambiguïté liée aux fuseaux et à l'heure d'été ; la partition journalière Cassandra et les calculs d'intervalles en dépendent.
- *Même mesure envoyée deux fois ?* Acceptée par le Collector, détectée et ignorée par le Storage (§6, §7).
- *Mesure très ancienne ?* Acceptée si valide (un capteur peut rejouer son tampon après une coupure) et rangée dans la partition de son jour. Une limite d'âge serait une règle métier à ajouter.
- *Erreur de format vs anomalie énergétique ?* Format → rejet HTTP 400, rien n'est stocké. Anomalie → mesure valide, **stockée** puis signalée par Analytics.

**Cassandra** — table pensée à partir de la requête « historique d'un capteur entre deux dates » :

```sql
CREATE TABLE smartenergy.measurements_by_sensor_day (
  sensor_id text, day date, measured_at timestamp,
  power_kw double, voltage double, temperature double,
  PRIMARY KEY ((sensor_id, day), measured_at)
) WITH CLUSTERING ORDER BY (measured_at DESC);
```

La clé de partition `(sensor_id, day)` borne la taille de chaque partition : un capteur très actif n'accumule pas des années de mesures dans une seule partition. Le tri décroissant sert la dernière mesure en premier. Une plage de dates se lit en une requête par jour (31 jours maximum côté API).

**MongoDB** — `sensors` (`_id` = identifiant, nom, bâtiment, étage, `maxPowerKw`, actif, tags) et `daily_aggregates` (`_id` = `capteur|date`, count, somme, min, max, nombre d'anomalies). **Redis** — stream `energy-measurements`, groupe `storage-group`, dead-letter `energy-measurements-dlq`, cache `latest:{sensorId}`.

## 5. Conception et réalisation des services

**Simulateur** (Python, bibliothèque standard) — P(t) = P_base + A·sin(2πt/T) + ε avec bruit gaussien, un P_base et une phase par capteur, tension et température plausibles ; injection d'une anomalie (puissance × 3 pendant quelques secondes) ; paramétrable par options ou variables d'environnement ; mode `--dry-run`.

**Collector Service** — `POST /api/measurements` : validation, publication `XADD`, réponse 202 ; 400 avec la liste des champs fautifs (`ProblemDetail`) ; 503 si Redis est indisponible. Journalise l'identifiant du capteur et l'identifiant Redis du message.

**Storage Service** — consumer Redis (§6), persistance (§7), API : CRUD des capteurs, résumé quotidien, historique, dernière mesure. Un capteur inconnu est auto-enregistré avec le seuil par défaut (8 kW).

**Analytics Service** — voir §8. Le résumé quotidien du Storage (`daily-summary`) combine l'agrégat MongoDB (moyenne, min, max, anomalies) et l'énergie estimée calculée sur la partition Cassandra du jour.

Tous les services : OpenAPI/Swagger (`/swagger-ui.html`), Actuator, `/api/health`, profils Spring (`default` pour localhost, `docker` pour le conteneur), configuration par variables d'environnement.

## 6. Gestion du flux Redis

Notions mises en œuvre : *stream*, identifiant d'événement, *consumer group* (`storage-group`), *consumer* (`storage-consumer-1`), accusé de traitement (`XACK`), message en attente (*pending*).

Boucle du consumer :
1. relire d'abord les messages déjà livrés à ce consumer mais jamais acquittés (offset `0`) — c'est ce qui permet la reprise après arrêt ou panne ;
2. sinon, lire les nouveaux messages (`>`, blocage 2 s, lots de 50) ;
3. pour chaque message : parser, persister, **puis seulement** acquitter (`XACK`, puis `XDEL` pour que le flux ne contienne que le backlog) ;
4. message illisible → envoyé en *dead-letter* puis acquitté (inutile de le rejouer) ; erreur de persistance → message laissé *pending*, nouvel essai après 3 s.

**Expérience demandée (arrêt volontaire du Storage)** : Storage arrêté, 5 mesures envoyées → `XLEN` = 5 (conservées) ; Storage relancé → les 5 sont persistées, `XLEN` = 0, 0 *pending*. Vérifié par le script de bout en bout et par un test d'intégration (§10).

## 7. Persistance Cassandra et MongoDB

Pour chaque message, dans l'ordre : (1) `INSERT … IF NOT EXISTS` dans Cassandra ; (2) création éventuelle du capteur et récupération de son seuil (`findAndModify` + `upsert`, une requête) ; (3) mise à jour atomique de l'agrégat du jour ; (4) mise à jour du cache Redis (best effort) ; (5) accusé de réception.

**Idempotence.** Un même événement peut être traité deux fois (arrêt entre l'écriture et l'acquittement). La clé primaire `(sensor_id, day, measured_at)` identifie une mesure et l'insertion conditionnelle indique si la ligne est nouvelle : un doublon ne modifie ni l'agrégat ni le cache et est compté (`smartenergy_measurements_duplicates_total`). Coût : une transaction légère (Paxos) par écriture — voir §10 (performance). Alternative écartée : l'upsert simple, qui laisserait les compteurs MongoDB se fausser en cas de rejeu.

**Limite assumée.** Si le service s'arrête entre l'étape 1 et l'étape 3, l'agrégat du jour omet cette mesure (le rejeu la voit comme doublon). Cassandra reste la source de vérité et Analytics recalcule depuis elle.

## 8. Module Analytics et IA

**Niveau A — descriptif** : nombre de mesures, moyenne, min, max, dépassements de seuil et énergie estimée E ≈ Σ Pᵢ·Δtᵢ (kWh), Δt étant l'écart réel entre deux mesures successives ; les trous de plus d'une heure ne sont pas comptés pour ne pas inventer de consommation.

**Niveau B — anomalies** : seuil par capteur (P > P_max) et score z = (P − μ)/σ avec |z| > 3 (seuil réglable). *Limite observée* : μ et σ sont calculés sur la période qui contient les anomalies ; avec 5 points anormaux sur 30, σ est gonflé et le pic de 12 kW n'obtient que z ≈ 2,3, alors que le seuil du capteur le détecte. Un z-score robuste (médiane/MAD) ou une fenêtre glissante hors anomalies serait préférable.

**Niveau C — prévision** : régression linéaire P(t) = a·t + b comparée à une baseline de persistance (dernière valeur) ; démarche : séparation chronologique 80 % entraînement / 20 % test, mesure de MAE et RMSE des deux modèles sur le test, **sélection du meilleur**, ré-entraînement sur tout l'historique, prévision de `horizon` pas (pas = médiane des intervalles).

| Information demandée par le sujet | Réponse |
|---|---|
| Fenêtre temporelle d'entrée | Historique demandé (`from`/`to`, 24 h par défaut), ≥ 10 points |
| Variables utilisées | Puissance en fonction du temps (univarié) |
| Valeurs manquantes | Aucune imputation : les points absents sont simplement absents ; trou > 1 h ignoré pour l'énergie |
| Normalisation | Aucune (régression linéaire non régularisée) |
| Métriques | MAE, RMSE du modèle retenu ; MAE de la droite (`linearMae`) et de la persistance (`baselineMae`) |

*Résultat honnête* : sur les données simulées (sinusoïde), la droite est moins bonne que la persistance (MAE 0,40 contre 0,30 kW), car une droite ne représente pas un cycle ; le service retient alors la persistance (`"model":"persistence"`) plutôt que de proposer un modèle pire que le naïf. Un vrai modèle (Holt-Winters, LSTM exporté en ONNX et chargé avec DJL) devrait au moins battre ces deux références. Le dossier `models/` est prévu pour cela mais reste vide.

## 9. Conteneurisation et déploiement

`docker compose up -d --build` démarre 3 bases (MongoDB 7, Redis 7 avec persistance AOF, Cassandra 5.0), une tâche d'initialisation (`cassandra-init`, exécute `scripts/init-cassandra.cql`, idempotent) et les 3 services. Points de conception :
- *Dockerfile* multi-étapes (Maven → JRE 17), utilisateur non-root, profil `docker` activé ;
- `depends_on` avec `condition: service_healthy` / `service_completed_successfully` : les services n'attendent pas à l'aveugle que Cassandra soit prête (≈ 1 min) ;
- volumes nommés : `docker compose down` puis `up` conserve les données (vérifié) ;
- bases **non exposées** sur l'hôte ; `docker-compose.dev.yml` les expose sur `127.0.0.1` pour le développement ;
- simulateur dans un profil `sim` démarré à la demande.

## 10. Tests et résultats

| Niveau | Contenu | Résultat |
|---|---|---|
| Unitaires (22) | validation HTTP, parsing des événements, logique de persistance (doublon, anomalie), énergie (résumé quotidien et analytics), détection, prévision | 22/22 |
| Intégration JUnit + Testcontainers (6), vrais Redis/Cassandra/MongoDB | API → Redis (202 crée un événement, 400 n'en crée aucun) ; Redis → Storage → Cassandra → MongoDB → API ; doublon stocké une fois ; message illisible → dead-letter sans bloquer ; messages ajoutés Storage arrêté traités après redémarrage | 6/6 |
| Bout en bout sur la plateforme Docker (`scripts/e2e-test.sh`) | scénarios 1 à 7 du sujet, idempotence, redémarrage complet | 18/18 |
| Simulateur 5 capteurs × 2 min | 300 envoyées, 300 acceptées, 300 stockées | 0 perte |

Tableau de preuve complet : `docs/tests.md`.

**Performance** (`docs/performance.md`, 259 200 mesures en base, machine unique partagée, ordres de grandeur) :
- lecture d'un historique de 1 h : p50 30 ms ; d'un jour (10 000 points) : p50 108 ms ; `/latest` : 1 ms (cache) / 2 ms (Cassandra) ;
- Analytics sur 43 200 points : p50 ≈ 400 ms, croissance linéaire avec le volume ;
- Collector : jusqu'à ~2 000 requêtes/s acceptées, p95 < 16 ms, 0 erreur ;
- **goulot = Storage** (~400 mesures/s : un consumer, un LWT par message) ; le flux Redis absorbe l'excédent puis se vide, sans perte. Pistes : plusieurs consommateurs dans le groupe, écritures par lots, agrégats lus dans MongoDB plutôt que recalculés.

## 11. Sécurité, limites et difficultés rencontrées

**Sécurité mise en place** : aucun secret dans Git (variables d'environnement, `.env` ignoré, `.env.example` fourni) ; entrées validées ; bases non exposées par défaut ; conteneurs non-root ; profils de configuration. **Endpoints sensibles identifiés** : `POST /api/measurements`, `POST/PUT /api/sensors`, endpoints Actuator. **Non réalisé** : authentification/autorisation (Spring Security, JWT, rôles), HTTPS — aujourd'hui toute personne atteignant les ports peut écrire.

**Limites** : prévision simple (sans saisonnalité) ; z-score sensible aux anomalies nombreuses ; livraison *at-least-once* avec fenêtre résiduelle sur l'agrégat (§7) ; Cassandra mono-nœud (facteur de réplication 1) ; un seul consumer Storage ; pas de Prometheus/Grafana (les métriques `smartenergy_*` sont exposées au format Prometheus mais non collectées) ; extensions de niveau 2 non réalisées.

**Difficultés rencontrées**
- JDK 21 de la machine sans compilateur (JRE seul) → build avec JDK 17, qui est aussi la cible du sujet.
- Docker récent incompatible avec le Testcontainers fourni par Spring Boot 3.3 (API client trop ancienne) : les tests s'ignoraient silencieusement ; correction en forçant `testcontainers.version` 1.21.4 et vérification que les tests s'exécutent bien (0 *skipped*).
- Cassandra met ≈ 1 min à démarrer → healthcheck et `depends_on` conditionnels, tâche d'initialisation séparée.
- Test de doublon en bout en bout d'abord faux : l'horodatage était recalculé à chaque appel, donc la « même » mesure ne l'était pas ; horodatages désormais déterministes.

## 12. Améliorations possibles

1. Modèle de prévision saisonnier (Holt-Winters puis LSTM exporté en ONNX, chargé par DJL) évalué contre la persistance.
2. Authentification JWT avec rôles ADMIN/VIEWER, HTTPS via reverse proxy.
3. Prometheus + Grafana (état de santé, mesures reçues/rejetées, backlog Redis, latence de prévision).
4. Mise à l'échelle du Storage : plusieurs consumers, écritures par lots ; Kafka à plus grande échelle.
5. Z-score robuste ; notifications lors d'une anomalie ; interface Web temps réel.

## 13. Conclusion

SmartEnergy Hub démontre qu'une architecture distribuée simple peut collecter, stocker, analyser et exposer des mesures énergétiques sans perte, y compris lorsqu'un composant s'arrête : Redis Streams découple la réception du stockage, Cassandra et MongoDB sont utilisés pour ce qu'ils font de mieux, et l'idempotence évite que les rejeux faussent les résultats. Le MVP est complet, testé à trois niveaux (unitaire, intégration avec de vraies bases, bout en bout) et mesuré. Les points faibles — prévision, sécurité applicative, passage à l'échelle du Storage — sont identifiés, chiffrés quand c'était possible, et listés comme travaux futurs plutôt que masqués.

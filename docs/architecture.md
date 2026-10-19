# Architecture et justification des choix

## Vue d'ensemble

```
Simulateur IoT ─HTTP POST─▶ Collector Service ─XADD─▶ Redis Stream "energy-measurements"
                                                              │ XREADGROUP (group storage-group)
                                                              ▼
                                                      Storage Service
                                       INSERT ... IF NOT EXISTS │            │ upsert $inc/$min/$max
                                                                ▼            ▼
                                                           Cassandra      MongoDB
                                                      (mesures brutes)  (capteurs, agrégats)
                                                                ▲            ▲
                                                                └── Analytics Service ──▶ API REST
```

Un **dashboard** web (nginx, port 3000) lit les trois API par un relais nginx et ne contient aucune logique métier.

Redis sert aussi de **cache** : hash `latest:{sensorId}` (dernière mesure, TTL 1 jour) mis à jour par le Storage Service, lu par `/latest`.

## Rôle de chaque composant

| Composant | Rôle | Pourquoi ce choix |
|---|---|---|
| Collector | Valide et accepte les mesures (HTTP 202) | Protège le système des données invalides ; ne dépend que de Redis → reste disponible même si les bases sont en panne |
| Redis Streams | Tampon + distribution | Découple réception et stockage ; consumer groups, accusés (XACK), messages « pending » rejouables |
| Storage | Seul service qui écrit dans les bases | Centralise la persistance et l'idempotence |
| Cassandra | Mesures brutes | Écritures massives, lecture rapide par (capteur, jour), croissance horizontale |
| MongoDB | Capteurs (configuration évolutive), agrégats quotidiens | Documents à structure variable (tags, seuils, bâtiment) |
| Analytics | Indicateurs, anomalies, prévision | Calculs lus depuis Cassandra (vérité) ; séparé pour évoluer indépendamment |

## Modélisation des données

**Mesure (contrat JSON)** — `sensorId` (obligatoire, ≤ 64 car.), `timestamp` (UTC ISO-8601, obligatoire), `powerKw` (≥ 0), `voltage` (0–1000 V), `temperature` (optionnelle, −60…150 °C).

**Cassandra** — pensée à partir de la requête « historique d'un capteur entre deux dates » :

```sql
PRIMARY KEY ((sensor_id, day), measured_at) WITH CLUSTERING ORDER BY (measured_at DESC)
```

La clé de partition `(sensor_id, day)` borne la taille d'une partition (un capteur très actif n'accumule pas des années de mesures dans une seule partition). Le clustering DESC sert la dernière mesure en premier. Une plage de dates = une requête par jour (31 jours max côté API).

**MongoDB** — `sensors` (`_id` = sensorId, name, building, floor, maxPowerKw, active, tags) ; `daily_aggregates` (`_id` = `capteur|date`, count, sumPowerKw, min, max, anomalyCount). Mise à jour atomique côté serveur (`$inc`, `$min`, `$max`), sans lecture préalable. Un capteur inconnu est auto-enregistré avec le seuil par défaut (`DEFAULT_MAX_POWER_KW`, 8 kW).

**Redis** — stream `energy-measurements`, groupe `storage-group`, consumer `storage-consumer-1`, dead-letter `energy-measurements-dlq`, cache `latest:*`.

## Fiabilité : ce qui se passe en cas de panne

| Situation | Comportement |
|---|---|
| Storage arrêté | Collector continue d'accepter (202) ; les messages s'accumulent dans le flux Redis |
| Storage redémarre | Relit d'abord ses messages *pending* (livrés mais non acquittés), puis les nouveaux : rien n'est perdu |
| Cassandra/MongoDB indisponible | Le message n'est pas acquitté, il reste *pending* ; nouvel essai toutes les 3 s |
| Redis indisponible | Collector répond 503 (l'émetteur peut réessayer) ; Storage réessaie en boucle |
| Message Redis illisible | Envoyé en dead-letter puis acquitté (inutile de le rejouer) ; compteur `smartenergy_measurements_invalid_total` |
| Cache Redis en panne | Ignoré (best effort) ; `/latest` se replie sur Cassandra |

L'acquittement (`XACK`, puis `XDEL` pour que le flux ne contienne que le backlog) n'a lieu qu'**après** persistance réussie.

### Idempotence

Un même événement peut être traité deux fois (redémarrage entre l'écriture et l'acquittement). Stratégie :
1. la clé primaire Cassandra `(sensor_id, day, measured_at)` identifie une mesure ;
2. l'INSERT est conditionnel (`IF NOT EXISTS`) : si la ligne existe, c'est un doublon → ni agrégat ni cache ne sont touchés ;
3. un doublon est compté (`smartenergy_measurements_duplicates_total`).

Coût : un LWT (Paxos) par écriture, acceptable ici ; pour un débit très élevé on préférerait un upsert simple et des agrégats recalculés.

## Réponses aux questions du guide (§9.3)

- **Pourquoi UTC ?** Les capteurs et serveurs peuvent être dans des fuseaux ou des heures d'été différents ; UTC rend les comparaisons, les partitions journalières (`day`) et les calculs d'intervalles non ambigus.
- **Même mesure envoyée deux fois ?** Le Collector l'accepte (il ne connaît pas le stockage) ; le Storage Service la détecte via l'INSERT conditionnel et l'ignore.
- **Mesure très ancienne ?** Acceptée si elle est valide : un capteur déconnecté peut rejouer son tampon. Elle atterrit dans la bonne partition (jour de sa date). Une limite d'âge serait une règle métier à ajouter dans la validation si besoin.
- **Erreur de format vs anomalie énergétique ?** Format (champ manquant, négatif, JSON cassé) → rejet HTTP 400, rien n'est stocké. Anomalie (puissance valide mais anormale, ex. 15 kW) → mesure **stockée** et signalée par Analytics (seuil, z-score).
- **Pourquoi HTTP 202 ?** La réponse confirme l'acceptation pour traitement, pas l'écriture dans toutes les bases (réception et stockage sont découplés).

## Observabilité

Actuator + Micrometer/Prometheus sur chaque service. Métriques métier : mesures reçues / rejetées / publiées / stockées / doublons / invalides, erreurs de persistance, messages en attente (`smartenergy_stream_pending`), longueur du flux (`smartenergy_stream_length`, backlog visible côté Collector même Storage arrêté), latence de traitement et de prévision.

## Sécurité

Secrets par variables d'environnement, jamais dans Git ; bases non exposées par défaut ; entrées validées ; conteneurs applicatifs non-root. **Non réalisé** : authentification/autorisation (Spring Security, JWT, rôles), HTTPS. Les endpoints sensibles à protéger en priorité : `POST /api/measurements` (écriture), `POST/PUT /api/sensors`, et les endpoints Actuator.

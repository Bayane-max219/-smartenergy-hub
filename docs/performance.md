# Mesures de performance

Ordres de grandeur, **pas un benchmark scientifique** : une seule machine (12 cœurs, 31 Go de RAM, déjà chargée par d'autres applications), Cassandra à 1 nœud, tout dans Docker sur le même hôte, une seule exécution. Reproductible avec `scripts/perf-test.py` (plateforme lancée) ; mesures du 2026-10-19.

```bash
python3 scripts/perf-test.py seed     # 259 200 mesures directement dans Cassandra
python3 scripts/perf-test.py read     # latence des lectures
python3 scripts/perf-test.py load     # débit du Collector + vidage du flux par le Storage
```

## 1. Lecture sur un jeu de données plus important (§13.2)

Jeu : 2 capteurs × 3 jours × 1 mesure / 2 s = **259 200 lignes** (30 partitions `(capteur, jour)`), chargées en 10 s (≈ 25 000 lignes/s avec `cqlsh COPY`). 30 requêtes mesurées par scénario après 3 d'échauffement, connexion HTTP persistante.

| Scénario | p50 | p95 | max | Taille réponse |
|---|---|---|---|---|
| Storage `/latest` — cache Redis | 1,1 ms | 1,5 ms | — | 0,1 Ko |
| Storage `/latest` — repli Cassandra | 2,1 ms | 2,5 ms | — | 0,1 Ko |
| Storage historique 1 h (~1 800 pts) | 29,7 ms | 55,5 ms | 66,8 ms | 193 Ko |
| Storage historique 1 jour (10 000 pts, `limit`) | 108 ms | 162 ms | 163 ms | 1,08 Mo |
| Storage historique 3 jours (10 000 pts, `limit`) | 141 ms | 211 ms | 263 ms | 1,08 Mo |
| Analytics `summary` 1 jour (~43 200 pts) | 397 ms | 771 ms | 843 ms | 0,25 Ko |
| Analytics `summary` 2 jours (~86 400 pts) | 878 ms | 1 126 ms | 1 293 ms | 0,25 Ko |
| Analytics `anomalies` 1 jour | 440 ms | 759 ms | 838 ms | 0,16 Ko |
| Analytics `forecast` 1 jour | 433 ms | 557 ms | 600 ms | 0,7 Ko |

Côté Cassandra (`nodetool tablestats`) : latence locale de lecture 0,64 ms, d'écriture 0,16 ms, 2,5 Mo sur disque pour 259 200 lignes.

**Commentaires**
- Lire une partition (capteur, jour) est très rapide : le coût d'un historique est dominé par la **sérialisation JSON et le transfert** (≈ 0,1 ms par 100 points), pas par Cassandra. C'est ce que permet le modèle « une partition par capteur et par jour » : la requête ne touche que les partitions concernées.
- Le temps d'un historique de 3 jours à 10 000 points reste proche de celui d'un jour : la `limit` plafonne le travail, la requête s'arrête dès que la limite est atteinte (jours parcourus du plus récent au plus ancien).
- Les calculs Analytics croissent **linéairement avec le nombre de points lus** (≈ 10 ms pour 1 000 points : lecture + mapping Spring Data + calcul). Pour un tableau de bord il faudrait lire les agrégats MongoDB (déjà maintenus à l'écriture, `daily-summary`) plutôt que de recalculer depuis les mesures brutes.
- Le cache Redis divise par ~2 la latence de `/latest` ; le gain est modeste ici car Cassandra sert déjà la dernière mesure d'une partition en 2 ms.

## 2. Charge sur la chaîne d'ingestion (§20.2)

3 000 requêtes `POST /api/measurements` par palier (10 capteurs, timestamps uniques), puis attente du vidage complet du flux Redis par le Storage Service (1 seul consumer). « Bout-en-bout » = mesures stockées / (envoi + vidage).

| Threads clients | Débit d'acceptation | p50 | p95 | p99 | Erreurs | Temps de vidage après envoi | Débit bout-en-bout |
|---|---|---|---|---|---|---|---|
| 1 | 322 req/s | 2,6 ms | 5,8 ms | 9,0 ms | 0 | 7,5 s | 178 mesures/s |
| 4 | 1 432 req/s | 2,3 ms | 5,4 ms | 7,8 ms | 0 | 9,0 s | 270 mesures/s |
| 16 | 2 088 req/s | 6,8 ms | 15,2 ms | 19,9 ms | 0 | 7,4 s | 339 mesures/s |

3 000 mesures stockées à chaque palier : **aucune perte, aucune erreur**.

**Commentaires**
- Le **Collector n'est pas le goulot** : il accepte jusqu'à ~2 000 req/s avec des latences de quelques ms, parce qu'il ne fait qu'une validation et un `XADD` Redis. Au-delà de 4 threads la latence augmente (saturation CPU de la machine partagée, 16 clients + 6 conteneurs).
- Le **Storage Service est le goulot** : ≈ 400 mesures/s en régime de vidage (un seul thread consumer, un `INSERT … IF NOT EXISTS` — transaction légère Paxos — plus 2 écritures MongoDB et 1 écriture du cache par message). Quand le débit d'entrée dépasse cela, le flux Redis absorbe l'écart (c'est son rôle de tampon) et se vide ensuite ; la latence perçue par l'émetteur reste faible. Les chiffres bout-en-bout incluent ≈ 1 s de polling `redis-cli` du script.
- Pistes mesurables : (1) plusieurs instances du Storage dans le même consumer group (Redis répartit les messages) ; (2) lecture par lots + écritures Cassandra asynchrones/batch ; (3) remplacer le LWT par un upsert simple si la détection de doublons peut être faite autrement (coût : agrégats à recalculer) ; (4) agrégats MongoDB mis à jour par lot.

## 3. Limites de ces mesures

Machine partagée et non isolée, une seule exécution (pas d'écart-type entre runs), client Python sur le même hôte, Cassandra mono-nœud sans réplication, pas de test de charge en lecture concurrente.

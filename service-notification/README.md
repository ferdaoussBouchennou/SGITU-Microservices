# G5 - Microservice Notification (SGITU)

Microservice de notifications (Email, SMS, Push, LOG) pour l'écosystème SGITU.  
Port par défaut : **8085**.

---

## Prérequis

| Outil | Version recommandée |
|--------|---------------------|
| [Docker Desktop](https://www.docker.com/products/docker-desktop/) | Récent (Compose v2) |
| [Java](https://adoptium.net/) (optionnel, lancement hors Docker) | **21** |
| [Maven](https://maven.apache.org/) (optionnel) | 3.9+ |

Ressources : prévoir au moins **4 Go de RAM** pour Docker (Kafka + MySQL + le service Java). Le premier démarrage peut prendre **2 à 3 minutes**.

---

## Démarrage rapide (Docker - recommandé)

### 1. Cloner et se placer dans le dossier du service

```bash
cd service-notification
```

### 2. Créer le fichier `.env`

Le fichier `.env` n'est pas versionné (voir `.gitignore`). Créez-le à la racine de `service-notification` :

```env
# ── MySQL ─────────────────────────────
MYSQL_ROOT_PASSWORD=root_sgitu_2026
MYSQL_PASSWORD=sgitu_pass_2026

# ── JWT (secret partagé fourni par G3 — Authentification) ──
JWT_SECRET=SGITU_G3_JWT_SECRET_KEY_CHANGE_ME_IN_PRODUCTION_256BITS!!

# ── SMTP (ex. Gmail) ───────────────────
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=votre-email@gmail.com
MAIL_PASSWORD=votre_mot_de_passe_application
MAIL_FROM=noreply@sgitu.ma

# ── Twilio (SMS) — optionnel ───────────
TWILIO_ACCOUNT_SID=
TWILIO_AUTH_TOKEN=
TWILIO_FROM_PHONE=
```

> **Important :** sans identifiants Twilio, le canal **SMS** renverra une erreur métier (`TWILIO_NOT_CONFIGURED`) mais le service démarre normalement.

### 3. Placer les credentials Firebase (PUSH)

Copiez votre fichier de compte de service Firebase à la racine du projet :

```
service-notification/firebase-adminsdk.json
```

> Si ce fichier est absent au premier `docker compose up`, Docker peut créer un **dossier** vide à la place du fichier et empêcher le démarrage. Supprimez le dossier fantôme puis replacez le vrai fichier JSON.

### 4. Lancer la stack

```bash
docker compose up -d --build
```

Services démarrés :

| Service | URL / port |
|---------|------------|
| **G5 Notification** | http://localhost:8085 |
| MySQL | `localhost:3314` (base `notifications_db`) |
| Kafka | `localhost:9093` (broker), `localhost:29093` (host) |
| Kafka UI | http://localhost:8091 |
| phpMyAdmin | http://localhost:8889 |

### 5. Vérifier que le service est prêt

Attendez que Kafka et MySQL soient **Healthy**, puis testez :

```bash
curl http://localhost:8085/api/notifications/health
```

Réponse attendue :

```json
{"status":"UP","timestamp":"..."}
```

Consultez les logs si besoin :

```bash
docker logs -f notification-service
```

Recherchez la ligne `Started G5Application` (démarrage complet).

---

## Interfaces utiles

| Interface | URL |
|-----------|-----|
| Health (public) | http://localhost:8085/api/notifications/health |
| Swagger UI | http://localhost:8085/swagger-ui.html |
| OpenAPI JSON | http://localhost:8085/api-docs |
| Actuator health | http://localhost:8085/actuator/health *(JWT requis)* |

---

## Tests API (Postman)

Une collection complète est fournie ici :

```
postman/G5-Notification-Service.postman_collection.json
```

1. Importer la collection dans Postman.
2. Vérifier la variable de collection `jwtSecret` (identique au `JWT_SECRET` du `.env`).
3. Les tokens JWT (`userToken`, `adminToken`) sont **générés automatiquement** par un script pre-request.
4. Lancer d'abord `0. Sante & Observabilite` → `GET /health`, puis le reste dans l'ordre.

---

## Lancement en local sans Docker (avancé)

Utile pour le développement Java uniquement. Il faut **MySQL** et **Kafka** accessibles (par ex. via `docker compose up -d mysql-notification kafka` sans le service notification).

### Variables d'environnement

```bash
# Windows PowerShell
$env:MYSQL_PASSWORD="sgitu_pass_2026"
$env:JWT_SECRET="SGITU_G3_JWT_SECRET_KEY_CHANGE_ME_IN_PRODUCTION_256BITS!!"
```

Le profil par défaut (`application.yml`) pointe vers :

- MySQL : `localhost:3314`
- Kafka : `localhost:9093`

### Compiler et lancer

```bash
./mvnw spring-boot:run
# ou
./mvnw.cmd spring-boot:run
```

---

## Arrêt et nettoyage

```bash
# Arrêter les conteneurs
docker compose down

# Arrêter + supprimer les volumes (réinitialise MySQL et Kafka)
docker compose down -v
```

Conteneur orphelin éventuel (ancien MailHog) :

```bash
docker compose up -d --remove-orphans
```

---

## Dépannage

### Postman : « No response » ou HTTP 000

Le service n'est pas encore démarré. Attendez 2–3 min puis retestez `/api/notifications/health`. Vérifiez :

```bash
docker ps
docker logs --tail 50 notification-service
```

### Le conteneur redémarre en boucle (`RestartCount` élevé)

- Vérifiez que `firebase-adminsdk.json` est un **fichier** JSON valide, pas un dossier.
- Consultez les logs : `docker logs notification-service`.

### Erreurs Twilio / SMS

Normal si `TWILIO_*` est vide dans `.env`. Les autres canaux (EMAIL, PUSH, LOG) fonctionnent.

### Warnings au `docker compose up`

```
TWILIO_* variable is not set. Defaulting to a blank string.
```

Sans impact sur le démarrage si vous n'utilisez pas le SMS.

---

## Architecture locale (rappel)

- **G3** : génère les JWT et fournit `JWT_SECRET`.
- **G10 (API Gateway)** : valide le trafic Frontend et injecte `X-User-Id`, `X-Roles`, etc.
- **G5** : double filtre - JWT direct (service-à-service) ou headers Gateway (Frontend).
- **Kafka** : consommation des événements G1, G2, G3, G6, G9.
- **G7** : appelle G5 en **REST** (`POST /api/notifications/send`) avec un Service JWT, sans Kafka.

---

## Structure du projet

```
service-notification/
├── src/main/java/ma/sgitu/g5/    # Code source Spring Boot
├── src/main/resources/
│   ├── application.yml           # Config (profil docker dans le même fichier)
│   └── logback-admin.xml
├── docker-compose.yml
├── Dockerfile
├── firebase-adminsdk.json        # À fournir localement (non versionné)
├── .env                          # À créer localement (non versionné)
├── postman/                      # Collection de tests
└── logs/                         # admin-operations.log, admin-notifications.log
```

---

## Équipe

Microservice **G5 - Notifications** - Projet SGITU.

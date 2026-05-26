# Formats JSON et Contrats d'Interface API — Service Abonnement

Ce document détaille les structures JSON exactes pour les requêtes et les réponses de chaque endpoint.

---

## 🎫 1. Abonnements Usagers (`/abonnements`)

### [POST] /abonnements/souscrire
- **Request Body** : Aucun
- **Path/Query Parameters** :
  - `userId` : Long (Obligatoire)
  - `planId` : Long (Obligatoire)
- **Response (201 Created)** :
```json
{
  "id": "Long",
  "userId": "Long",
  "plan": {
    "idPlan": "Long",
    "nomPlan": "String",
    "description": "String",
    "prix": "Double",
    "duree": "HEBDOMADAIRE | MENSUEL | TRIMESTRIEL | ANNUEL",
    "categorie": "ROLE_PASSENGER | ROLE_STUDENT",
    "transportType": "BUS | METRO | TRAMWAY | TRAIN",
    "estActif": "ACTIF | DESACTIVE | SUPPRIME",
    "maxDesactivation": "int",
    "minJoursEntreDesactivation": "int",
    "maxPeriodeDesactivation": "int",
    "createdAt": "LocalDateTime",
    "updatedAt": "LocalDateTime"
  },
  "dateDebut": "LocalDateTime | null",
  "dateFin": "LocalDateTime | null",
  "dateDemandeAnnulation": "LocalDateTime | null",
  "dateAnnulation": "LocalDateTime | null",
  "dateDerniereTentativeRemb": "LocalDateTime | null",
  "nbTentativesRemb": "int",
  "statut": "EN_ATTENTE_PAIEMENT | ACTIF | EXPIRE | SUSPENDU | DESACTIVE | ANNULATION_EN_COURS | ECHEC_PAIEMENT | ECHEC_REMBOURSEMENT | ANNULE",
  "remboursementId": "String | null",
  "paiementId": "String | null",
  "prixPaye": "Double",
  "renouvellementAuto": "Boolean",
  "createdAt": "LocalDateTime",
  "updatedAt": "LocalDateTime"
}
```

### [GET] /abonnements/{id}
- **Path/Query Parameters** :
  - `id` : Long — ID de l'abonnement
- **Response (200 OK)** : Objet `Abonnement` (voir structure ci-dessus)

### [GET] /abonnements/utilisateur/{userId}
- **Path/Query Parameters** :
  - `userId` : Long — ID de l'utilisateur
- **Response (200 OK)** :
```json
[
  { "id": "Long", "userId": "Long", "statut": "String", ... }
]
```

### [GET] /abonnements/utilisateur/{userId}/actif
- **Response (200 OK)** : Objet `Abonnement` unique ou 404.

### [GET] /abonnements/utilisateur/{userId}/complet
- **Response (200 OK)** : Wrapper Page Spring Data.
```json
{
  "content": [ { "id": "Long", ... } ],
  "totalElements": "Long",
  "totalPages": "int",
  "size": "int",
  "number": "int"
}
```

### [POST] /abonnements/{id}/annuler
- **Response (200 OK)** : Aucun body (Void).

### [POST] /abonnements/{id}/desactiver
- **Path/Query Parameters** :
  - `jours` : int (Obligatoire) — Nombre de jours de suspension
- **Response (200 OK)** : Aucun body (Void).

### [POST] /abonnements/{id}/renouveler-manuel
- **Response (200 OK)** : Objet `Abonnement` mis à jour.

### [PATCH] /abonnements/{id}/renouvellement-auto
- **Path/Query Parameters** :
  - `enable` : Boolean (Obligatoire) — true pour activer, false pour désactiver
- **Response (200 OK)** : Objet `Abonnement` mis à jour.

### [GET] /abonnements/{id}/historique-paiements
- **Response (200 OK)** :
```json
[
  {
    "id": "Long",
    "paiementId": "String | null",
    "dateRenouvellement": "LocalDateTime",
    "statut": "SUCCES | ECHOUE | EN_ATTENTE",
    "typeRenouvellement": "MANUEL | AUTOMATIQUE",
    "prixApplique": "Double",
    "createdAt": "LocalDateTime",
    "updatedAt": "LocalDateTime"
  }
]
```

---

## 🔗 2. Callbacks G6 (`/abonnements`)

### [POST] /abonnements/paiement/confirmation
- **Request Body** : (Obligatoire)
```json
{
  "transactionToken": "String",
  "status": "String",
  "message": "String"
}
```
- **Response (200 OK)** :
```json
{
  "statut": "String",
  "message": "String"
}
```

### [POST] /abonnements/remboursement/confirmation
- **Request Body** : (Obligatoire)
```json
{
  "transactionId": "String",
  "statut": "String",
  "montantRembourse": "Double",
  "motif": "String"
}
```
- **Response (200 OK)** :
```json
{
  "statut": "String",
  "message": "String"
}
```

---

## 👮 3. Administration (`/abonnements/admin`)
*Rôle requis : ROLE_ADMIN_G2*

### [POST] /abonnements/admin/{id}/suspendre
- **Path/Query Parameters** :
  - `motif` : String (Obligatoire)
- **Response (200 OK)** : Aucun body.

### [POST] /abonnements/admin/{id}/forcer-annulation
- **Path/Query Parameters** :
  - `motif` : String (Obligatoire)
- **Response (200 OK)** : Aucun body.

### [POST] /abonnements/admin/{id}/forcer-renouvellement
- **Response (200 OK)** : Aucun body.

---

## 🏗️ 4. Plans d'Abonnement (`/plans`)

### [GET] /plans
- **Response (200 OK)** : Wrapper Page contenant des objets `PlanAbonnement`.

### [POST] /plans
- **Request Body** : (Obligatoire)
```json
{
  "nomPlan": "String",
  "description": "String",
  "prix": "Double",
  "duree": "HEBDOMADAIRE | MENSUEL | TRIMESTRIEL | ANNUEL",
  "categorie": "ROLE_PASSENGER | ROLE_STUDENT",
  "transportType": "BUS | METRO | TRAMWAY | TRAIN",
  "estActif": "ACTIF | DESACTIVE | SUPPRIME",
  "maxDesactivation": "int",
  "minJoursEntreDesactivation": "int",
  "maxPeriodeDesactivation": "int"
}
```
- **Response (200 OK)** : Objet `PlanAbonnement` complet.

### [PUT] /plans/{id}
- **Request Body** : (Obligatoire) — Structure identique au POST ci-dessus.
- **Response (200 OK)** : Objet mis à jour.

### [DELETE] /plans/{id}
- **Response (204 No Content)** : Aucun body.

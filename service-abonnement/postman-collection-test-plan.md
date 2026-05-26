# Plan de Test Postman — Microservice Abonnement (G2)

Ce document contient l'intégralité des tests nécessaires pour valider le microservice, incluant les cas nominaux et les cas d'erreur.

## 📊 Tableau Récapitulatif des Tests

| Module | Méthode | Endpoint | Cas de Test | Statut Attendu |
| :--- | :--- | :--- | :--- | :--- |
| **Usagers** | POST | `/abonnements/souscrire` | Succès (Nominal) | 201 Created |
| **Usagers** | POST | `/abonnements/souscrire` | Plan inexistant | 404 Not Found |
| **Usagers** | GET | `/abonnements/{id}` | Succès | 200 OK |
| **Usagers** | GET | `/abonnements/{id}` | ID Inconnu | 404 Not Found |
| **Usagers** | POST | `/{id}/desactiver` | Jours négatifs | 400 Bad Request |
| **Usagers** | POST | `/{id}/desactiver` | Dépassement durée max | 422 Unprocessable |
| **Usagers** | POST | `/{id}/annuler` | Déjà annulé | 409 Conflict |
| **G6 Callback**| POST | `/paiement/confirmation`| Succès SUCCESS | 200 OK |
| **G6 Callback**| POST | `/paiement/confirmation`| Token Invalide | 404 Not Found |
| **Admin** | POST | `/admin/{id}/suspendre`| Sans rôle ADMIN_G2 | 403 Forbidden |
| **Plans** | POST | `/plans` | Succès Création | 200 OK |
| **Plans** | DELETE | `/plans/{id}` | Succès Suppression | 204 No Content |

---

## 🎫 1. Abonnements Usagers (`/abonnements`)

### [POST] /abonnements/souscrire
#### ✅ Test 1 — Cas nominal
- **URL** : `{{baseUrl}}/abonnements/souscrire?userId={{userId}}&planId={{planId}}`
- **Method** : `POST`
- **Expected Status** : `201 Created`
- **Tests Postman** :
```javascript
pm.test("Status code is 201", () => pm.response.to.have.status(201));
pm.test("Abonnement initialisé", () => {
    const jsonData = pm.response.json();
    pm.expect(jsonData.statut).to.eql("EN_ATTENTE_PAIEMENT");
    pm.expect(jsonData.userId).to.eql(parseInt(pm.environment.get("userId")));
    pm.environment.set("abonnementId", jsonData.id);
});
```

#### ❌ Test 2 — Plan Inexistant
- **URL** : `{{baseUrl}}/abonnements/souscrire?userId={{userId}}&planId=9999`
- **Expected Status** : `404 Not Found`

---

### [POST] /abonnements/{id}/desactiver
#### ✅ Test 1 — Succès
- **URL** : `{{baseUrl}}/abonnements/{{abonnementId}}/desactiver?jours=5`
- **Expected Status** : `200 OK`
- **Tests Postman** :
```javascript
pm.test("Désactivation acceptée", () => pm.response.to.have.status(200));
```

#### ❌ Test 2 — Valeur invalide (jours=0)
- **URL** : `{{baseUrl}}/abonnements/{{abonnementId}}/desactiver?jours=0`
- **Expected Status** : `400 Bad Request`

---

## 🔗 2. Callbacks G6 (`/abonnements`)

### [POST] /abonnements/paiement/confirmation
#### ✅ Test 1 — Confirmation Succès
- **URL** : `{{baseUrl}}/abonnements/paiement/confirmation`
- **Body** :
```json
{
  "transactionToken": "TOKEN-123",
  "status": "SUCCESS",
  "message": "Approved"
}
```
- **Tests Postman** :
```javascript
pm.test("Status 200", () => pm.response.to.have.status(200));
pm.test("Message confirmation", () => {
    pm.expect(pm.response.json().message).to.contain("succès");
});
```

---

## 👮 3. Administration (`/abonnements/admin`)

### [POST] /admin/{id}/suspendre
#### ❌ Test 1 — Absence de Rôle ADMIN
- **Headers** : `Authorization: Bearer {{userToken}}` (Simple user)
- **Expected Status** : `403 Forbidden`
- **Tests Postman** :
```javascript
pm.test("Access Denied", () => pm.response.to.have.status(403));
```

#### ✅ Test 2 — Succès avec ADMIN_G2
- **Headers** : `Authorization: Bearer {{adminToken}}`
- **URL** : `{{baseUrl}}/abonnements/admin/{{abonnementId}}/suspendre?motif=Test Admin`
- **Expected Status** : `200 OK`

---

## 🏗️ 4. Plans d'Abonnement (`/plans`)

### [POST] /plans
#### ✅ Test 1 — Création Nominal
- **Body** :
```json
{
  "nomPlan": "PLAN_POSTMAN_TEST",
  "prix": 50.0,
  "duree": "HEBDOMADAIRE",
  "categorie": "ROLE_PASSENGER",
  "transportType": "BUS",
  "estActif": "ACTIF"
}
```
- **Tests Postman** :
```javascript
pm.test("Plan créé", () => {
    pm.response.to.have.status(200);
    pm.environment.set("newPlanId", pm.response.json().idPlan);
});
```

### [DELETE] /plans/{id}
#### ✅ Test 1 — Suppression
- **URL** : `{{baseUrl}}/plans/{{newPlanId}}`
- **Expected Status** : `204 No Content`

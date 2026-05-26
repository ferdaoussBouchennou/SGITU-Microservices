# Cahier de Tests Postman — Microservice Abonnement (G2)

**Base URL** : `http://localhost:8082`

---

## 🟢 1. SCÉNARIO : Cycle de vie Complet (Succès)

### Étape 1 : Créer le Plan
`POST http://localhost:8082/plans`
```json
{
  "nomPlan": "TEST_AUTO",
  "prix": 100.0,
  "duree": "MENSUEL",
  "categorie": "ROLE_PASSENGER",
  "transportType": "BUS",
  "estActif": "ACTIF"
}
```

### Étape 2 : Souscrire (Devient EN_ATTENTE_PAIEMENT)
`POST http://localhost:8082/abonnements/souscrire?userId=10&planId=1`

### Étape 3 : Simuler Callback G6 SUCCÈS (Devient ACTIF)
`POST http://localhost:8082/abonnements/paiement/confirmation`
```json
{
  "transactionToken": "TOKEN-OK-123",
  "status": "SUCCESS",
  "message": "Paiement validé"
}
```

### Étape 4 : Vérifier l'accès (G6 Gates)
`GET http://localhost:8082/abonnements/users/10/actif`
*(Doit répondre `aUnAbonnementActif: true`)*

---

## 🔴 2. SCÉNARIO : Échec de Paiement

### Étape 1 : Souscrire à un nouveau plan
`POST http://localhost:8082/abonnements/souscrire?userId=11&planId=1`

### Étape 2 : Simuler Callback G6 ÉCHEC
`POST http://localhost:8082/abonnements/paiement/confirmation`
```json
{
  "transactionToken": "TOKEN-FAIL-456",
  "status": "FAILED",
  "message": "Solde insuffisant"
}
```
*(L'abonnement doit passer en statut `ECHEC_PAIEMENT`)*

---

## ⚠️ 3. SCÉNARIO : Règles Métier & Erreurs

### Cas A : Désactivation trop longue (Erreur 422)
*Tentez de désactiver pour 1000 jours alors que le max est 90.*
`POST http://localhost:8082/abonnements/1/desactiver?jours=1000`

### Cas B : ID inexistant (Erreur 404)
`GET http://localhost:8082/abonnements/999999`

### Cas C : Annulation impossible
*Tenter d'annuler un abonnement qui est déjà en statut `ANNULE`.*
`POST http://localhost:8082/abonnements/1/annuler`

### Cas D : Accès interdit (Erreur 403)
*Tenter un appel Admin sans les droits.*
`POST http://localhost:8082/abonnements/admin/1/suspendre?motif=test`

---

## ⚙️ 4. SCÉNARIO : Maintenance & Admin

### Forcer un renouvellement manuel
`POST http://localhost:8082/abonnements/1/renouveler-manuel`

### Vérifier l'historique complet d'un usager
`GET http://localhost:8082/abonnements/utilisateur/10/complet`

### Consulter l'historique des paiements d'un abonnement précis
`GET http://localhost:8082/abonnements/1/historique-paiements`

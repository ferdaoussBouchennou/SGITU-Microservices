# Guide detaille de test des endpoints - Service Paiement (G6)

Ce document te donne une procedure pratique pour tester **tous les endpoints importants** du microservice `service-paiement`.

## 1) Prerequis

- Service demarre en **HTTPS uniquement** sur `https://localhost:8086`
- Base de donnees MySQL locale attendue sur `localhost:3316` (voir `application.properties`)
- JWT obligatoire pour tous les endpoints sauf:
  - `/health`
  - `/swagger-ui/**`
  - `/v3/api-docs/**`

## 2) JWT de test (obligatoire)

Le service valide un JWT signe avec:

- `jwt.secret = SGITU_G6_JWT_SECRET_KEY_CHANGE_ME_IN_PRODUCTION_256BITS!!`

Le filtre lit le claim `sub` (subject) et verifie seulement la signature + expiration.

Payload minimal a mettre dans jwt.io (HS256):

```json
{
  "sub": "test-user",
  "exp": 1893456000
}
```

Ensuite, dans les requetes:

- Header: `Authorization: Bearer <TON_JWT>`

## 3) Variables utiles pour tests

- `BASE_URL = https://localhost:8086`
- `JWT = <TON_JWT>`
- `Content-Type: application/json`

> Comme le certificat TLS est auto-signe, utilise `-k` avec `curl.exe`.

## 4) Donnees seedees deja disponibles (DataInitializer)

Tu peux tester rapidement sans creer un compte de paiement depuis zero grace aux tokens deja presents:

- `CARD-TOKEN-001` (userId=1, actif, solde 1000)
- `MM-TOKEN-001` (userId=1, actif, solde 500)
- `CARD-TOKEN-005` (userId=5, **bloque**) -> utile pour scenario echec
- `CARD-TOKEN-004` (userId=4, solde 0) -> utile pour `INSUFFICIENT_BALANCE`

## 5) Smoke test rapide (ordre recommande)

1. `GET /health` (sans JWT) pour verifier que le service repond.
2. `GET /payment-accounts/user/1` (avec JWT) pour verifier l'acces securise.
3. `POST /payments` avec `CARD-TOKEN-001` (cas success).
4. `GET /payments/{paymentId}` pour verifier le statut.
5. `GET /payments/{paymentId}/invoice` pour recuperer la facture.
6. `POST /payments/{paymentId}/refund` pour tester remboursement.

## 6) Endpoints et exemples de test

## 6.1 Health

### GET `/health`
- Auth: non
- Reponse: `200 OK`, body texte: `G6 Payment Service - UP`

```powershell
curl.exe -k -X GET "https://localhost:8086/health"
```

## 6.2 Comptes de paiement

### POST `/payment-accounts/card`
Ajoute une carte et declenche OTP.

- Auth: JWT
- Statut attendu: `201 Created`
- Body:

```json
{
  "userId": 10,
  "cardNumber": "4532015112830366",
  "cvv": "123",
  "expiryMonth": 12,
  "expiryYear": 2027,
  "email": "user10@example.com"
}
```

```powershell
curl.exe -k -X POST "https://localhost:8086/payment-accounts/card" `
  -H "Authorization: Bearer <JWT>" `
  -H "Content-Type: application/json" `
  -d '{"userId":10,"cardNumber":"4532015112830366","cvv":"123","expiryMonth":12,"expiryYear":2027,"email":"user10@example.com"}'
```

Erreurs typiques:
- `400 Carte non reconnue`
- `400 CVV incorrect`
- `400 Carte bloquee ou expiree`

### POST `/payment-accounts/mobile-money`
Ajoute un compte mobile money et declenche OTP.

- Auth: JWT
- Statut attendu: `201 Created`
- Body:

```json
{
  "userId": 10,
  "phoneNumber": "0612345678",
  "provider": "INWI",
  "email": "user10@example.com"
}
```

Erreurs typiques:
- `400 Numero Mobile Money non reconnu`
- `400 Provider incorrect`
- `400 Compte Mobile Money bloque`

### POST `/payment-accounts/{paymentAccountId}/verify-otp`
Valide OTP pour activer le moyen de paiement.

- Auth: JWT
- Statut attendu: `200 OK`
- Body:

```json
{
  "otpCode": "123456"
}
```

Erreurs typiques:
- `400 Aucun OTP en attente`
- `400 OTP expire`
- `400 OTP incorrect`

### GET `/payment-accounts/user/{userId}`
- Auth: JWT
- Statut: `200 OK`

### GET `/payment-accounts/id/{id}`
- Auth: JWT
- Statut: `200 OK`

### DELETE `/payment-accounts/id/{id}`
- Auth: JWT
- Statut: `204 No Content`

## 6.3 Paiements

### POST `/payments`
Cree et traite un paiement.

- Auth: JWT
- Statut:
  - `201 Created` si `status = SUCCESS`
  - `200 OK` si `status = FAILED`
- Body exemple success:

```json
{
  "userId": 1,
  "sourceType": "TICKET",
  "sourceId": 999,
  "amount": 20.00,
  "paymentMethod": "CARD",
  "savedPaymentToken": "CARD-TOKEN-001",
  "email": "user1@example.com",
  "description": "Achat ticket test"
}
```

```powershell
curl.exe -k -X POST "https://localhost:8086/payments" `
  -H "Authorization: Bearer <JWT>" `
  -H "Content-Type: application/json" `
  -d '{"userId":1,"sourceType":"TICKET","sourceId":999,"amount":20.00,"paymentMethod":"CARD","savedPaymentToken":"CARD-TOKEN-001","email":"user1@example.com","description":"Achat ticket test"}'
```

Exemple echec (solde insuffisant):

```json
{
  "userId": 4,
  "sourceType": "TICKET",
  "sourceId": 1000,
  "amount": 40.00,
  "paymentMethod": "CARD",
  "savedPaymentToken": "CARD-TOKEN-004",
  "email": "user4@example.com"
}
```

`failureReason` possibles selon le service:
- `INVALID_TOKEN`
- `UNAUTHORIZED_TOKEN`
- `ACCOUNT_NOT_ACTIVE`
- `INSUFFICIENT_BALANCE`

### GET `/payments/{paymentId}`
- Auth: JWT
- Statut: `200 OK`
- Erreur: `400 Paiement introuvable ID: ...`

### GET `/payments/user/{userId}`
- Auth: JWT
- Statut: `200 OK`

### PUT `/payments/{paymentId}/cancel`
- Auth: JWT
- Statut: `200 OK` si paiement `PENDING`
- Erreur: `400 Seul un paiement PENDING peut etre annule`

## 6.4 Factures

### GET `/invoices/{invoiceId}`
- Auth: JWT
- Statut: `200 OK`

### GET `/invoices/number/{invoiceNumber}`
- Auth: JWT
- Statut: `200 OK`

### GET `/payments/{paymentId}/invoice`
- Auth: JWT
- Statut: `200 OK`

### GET `/invoices/user/{userId}`
- Auth: JWT
- Statut: `200 OK`

## 6.5 Remboursements

### POST `/payments/{paymentId}/refund`
- Auth: JWT
- Statut:
  - `201 Created` si `status = REFUNDED`
  - `200 OK` si `status = REFUND_FAILED`
  - `400` pour validation/metier
- Body:

```json
{
  "amount": 10.00,
  "reason": "Annulation client"
}
```

```powershell
curl.exe -k -X POST "https://localhost:8086/payments/1/refund" `
  -H "Authorization: Bearer <JWT>" `
  -H "Content-Type: application/json" `
  -d '{"amount":10.00,"reason":"Annulation client"}'
```

Erreurs metier:
- `400 Seul un paiement SUCCESS peut etre rembourse`
- `400 Le montant du remboursement ne peut pas depasser le montant du paiement`

### GET `/refunds/{refundId}`
- Auth: JWT
- Statut: `200 OK`

### GET `/refunds/payment/{paymentId}`
- Auth: JWT
- Statut: `200 OK`

### GET `/refunds/user/{userId}`
- Auth: JWT
- Statut: `200 OK`

## 6.6 Endpoints de test (optionnel)

### GET `/test-cards`
- Auth: JWT
- Retourne cartes de test (sans numero complet, avec last4, solde, provider, status)

### GET `/test-mobile-money-accounts`
- Auth: JWT
- Retourne comptes mobile money de test (maskedPhone, provider, solde, status)

### POST `/test/notification/{paymentId}?email=...`
- Auth: JWT
- Force envoi d'une notif de paiement SUCCESS (utile debug integration G5)

### GET `/test/notification-format/{paymentId}?email=...`
- Auth: JWT
- Retourne le payload de notification genere

## 7) Codes de reponse frequents

- `200 OK`: lecture ou operation non-creatrice
- `201 Created`: creation effective (paiement success, ajout moyen de paiement, refund success)
- `204 No Content`: suppression d'un payment account
- `400 Bad Request`: erreur metier (`BadRequestException`)
- `401/403`: JWT absent/invalide (selon la chaine security active)
- `500`: erreur imprevue (`RuntimeException`)

## 8) Conseils de debug

- Verifier que tu utilises bien `https://` (pas `http://`)
- Verifier expiration JWT (`exp`) et secret de signature
- Verifier correspondance `userId` <-> `savedPaymentToken`
- Lire les logs Spring (`logging.level.ma.sgitu.payment=DEBUG`) pour voir la cause exacte


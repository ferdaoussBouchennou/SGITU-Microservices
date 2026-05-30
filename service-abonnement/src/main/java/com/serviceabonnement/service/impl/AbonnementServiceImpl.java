package com.serviceabonnement.service.impl;

import com.serviceabonnement.client.AnalyseClient;
import com.serviceabonnement.client.PaiementClient;
import com.serviceabonnement.client.UtilisateurServiceClient;
import com.serviceabonnement.dto.external.PaymentRequestDTO;
import com.serviceabonnement.dto.external.PaymentResponseDTO;
import com.serviceabonnement.dto.external.UserDTO;
import com.serviceabonnement.entity.Abonnement;
import com.serviceabonnement.entity.PlanAbonnement;
import com.serviceabonnement.enums.StatutAbonnement;
import com.serviceabonnement.exception.AbonnementNotFoundException;
import com.serviceabonnement.exception.AbonnementStatutInvalideException;
import com.serviceabonnement.exception.BaseException;
import com.serviceabonnement.exception.ConflictException;
import com.serviceabonnement.exception.PaiementServiceException;
import com.serviceabonnement.exception.PlanNotFoundException;
import com.serviceabonnement.exception.RegleMetierException;
import com.serviceabonnement.exception.UtilisateurNotFoundException;
import com.serviceabonnement.producer.SubscriptionEventPublisher;
import com.serviceabonnement.repository.AbonnementRepository;
import com.serviceabonnement.repository.AnalytiqueTraceRepository;
import com.serviceabonnement.repository.DesactivationRepository;
import com.serviceabonnement.repository.PlanAbonnementRepository;
import com.serviceabonnement.repository.RenouvellementRepository;
import com.serviceabonnement.repository.SuspensionAdminRepository;
import com.serviceabonnement.service.AbonnementService;
import com.serviceabonnement.entity.SuspensionAdmin;
import com.serviceabonnement.entity.AnalytiqueTrace;
import com.serviceabonnement.entity.Desactivation;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.serviceabonnement.entity.Renouvellement;

@Service
@RequiredArgsConstructor
@Slf4j
public class AbonnementServiceImpl implements AbonnementService {

    private final AbonnementRepository abonnementRepository;
    private final PlanAbonnementRepository planRepository;
    private final UtilisateurServiceClient userClient;
    private final PaiementClient paiementClient;
    private final AnalyseClient analyseClient;
    private final SubscriptionEventPublisher eventPublisher;
    private final AnalytiqueTraceRepository analytiqueTraceRepository;
    private final DesactivationRepository desactivationRepository;
    private final RenouvellementRepository renouvellementRepository;
    private final SuspensionAdminRepository suspensionAdminRepository;

    @Autowired
    @Lazy
    private AbonnementServiceImpl self;

    @Override
    @Transactional
    @CircuitBreaker(name = "externalService", fallbackMethod = "souscrireFallback")
    @Retry(name = "externalService")
    @Bulkhead(name = "externalService")
    public Abonnement souscrire(Long userId, Long planId, String email) {
        log.info("Tentative de souscription pour l'utilisateur {} au plan {}", userId, planId);

        if (email == null) email = "";

        // 1. Vérification Utilisateur via G3
        UserDTO user;
        try {
            user = userClient.getUserById(userId);
        } catch (Exception e) {
            log.warn("Impossible de vérifier l'utilisateur {} via G3: {}", userId, e.getMessage());
            user = null;
        }
        if (user == null || !user.isActive()) {
            throw new UtilisateurNotFoundException(userId);
        }

        // 2. Récupération du Plan
        PlanAbonnement plan = planRepository.findById(planId)
                .orElseThrow(() -> new PlanNotFoundException(planId));

        // 3. Vérification d'éligibilité via les rôles du JWT (from SecurityContext)
        List<String> userRoles = extractUserRoles();
        if (!isEligibleForPlan(userRoles, plan.getCategorie().name())) {
            log.error("Utilisateur {} non éligible pour le plan {}", userId, plan.getCategorie().name());
            throw new RegleMetierException("Utilisateur non eligible pour le plan " + plan.getCategorie().name() + ".");
        }

        // 4. Calcul du prix (plan-change vs nouvelle souscription)
        java.util.Optional<Abonnement> abonnementMemeTransport = abonnementRepository
                .findByUserIdAndStatut(userId, StatutAbonnement.ACTIF).stream()
                .filter(a -> a.getPlan().getTransportType() == plan.getTransportType())
                .findFirst();

        double prixAPayer = plan.getPrix();

        if (abonnementMemeTransport.isPresent()) {
            Abonnement abonnementActif = abonnementMemeTransport.get();
            validerTransitionAbonnement(abonnementActif, plan);
            double valeurRestante = calculerRemboursement(abonnementActif);
            if (valeurRestante > 0) {
                prixAPayer = plan.getPrix() >= valeurRestante
                        ? plan.getPrix() - valeurRestante   // Upgrade / Switch
                        : 0.0;                              // Downgrade (remboursement à l'activation)
            }
        }

        // 5. Création du brouillon d'abonnement (EN_ATTENTE_PAIEMENT)
        Abonnement abonnement = Abonnement.builder()
                .userId(userId)
                .plan(plan)
                .prixPaye(prixAPayer)
                .statut(StatutAbonnement.EN_ATTENTE_PAIEMENT)
                .renouvellementAuto(true)
                .build();
        abonnement = abonnementRepository.save(abonnement);

        sendToAnalyse(abonnement, "SOUSCRIPTION_INITIALE", prixAPayer);

        // Récupération du téléphone depuis le user déjà chargé
        String phone = (user.getProfile() != null) ? user.getProfile().getPhone() : null;
        if (email.isEmpty()) email = user.getEmail();

        // 6. Initiation Paiement (G6)
        try {
            PaymentRequestDTO paymentRequest = PaymentRequestDTO.builder()
                    .userId(userId)
                    .sourceType("SUBSCRIPTION")
                    .sourceId(abonnement.getId())
                    .amount(prixAPayer)
                    .paymentMethod(getRandomPaymentMethod())
                    .email(email)
                    .description("Souscription plan " + plan.getNomPlan())
                    .build();
            PaymentResponseDTO paymentResponse = paiementClient.initierPaiement(paymentRequest);
            abonnement.setPaiementId(paymentResponse.getTransactionId());
            abonnementRepository.save(abonnement);
        } catch (Exception e) {
            log.error("Échec de l'initiation du paiement pour l'abonnement {}", abonnement.getId());
            eventPublisher.publishEchecSouscription(userId, email, phone, plan.getNomPlan(), "Erreur service paiement");
            throw new PaiementServiceException("Impossible d'initier le paiement", e);
        }

        return abonnement;
    }

    @Override
    public Abonnement getAbonnementById(Long id) {
        return abonnementRepository.findById(id)
                .orElseThrow(() -> new AbonnementNotFoundException("Abonnement introuvable avec l'ID : " + id));
    }

    @Override
    public List<Abonnement> getAbonnementsByUtilisateur(Long userId) {
        return abonnementRepository.findByUserId(userId);
    }

    @Override
    public Page<Abonnement> getFullHistory(Long userId, Pageable pageable) {
        return abonnementRepository.findByUserId(userId, pageable);
    }

    @Override
    public Abonnement getActif(Long userId) {
        return abonnementRepository.findByUserId(userId).stream()
                .filter(a -> a.getStatut() == StatutAbonnement.ACTIF)
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<Renouvellement> getHistoriquePaiements(Long abonnementId) {
        return renouvellementRepository.findByAbonnementId(abonnementId);
    }

    @Override
    @Transactional
    public Abonnement toggleAutoRenouvellement(Long abonnementId, boolean enable) {
        Abonnement abonnement = getAbonnementById(abonnementId);
        abonnement.setRenouvellementAuto(enable);
        return abonnementRepository.save(abonnement);
    }

    @Override
    @Transactional
    public void forcerRenouvellement(Long abonnementId) {
        Abonnement abonnement = getAbonnementById(abonnementId);
        PlanAbonnement plan = abonnement.getPlan();

        // Même logique que renouveler mais marqué comme MANUEL
        UserDTO user = userClient.getUserById(abonnement.getUserId());
        PaymentRequestDTO paymentRequest = PaymentRequestDTO.builder()
                .userId(abonnement.getUserId())
                .sourceType("SUBSCRIPTION")
                .sourceId(abonnementId)
                .amount(plan.getPrix())
                .paymentMethod(getRandomPaymentMethod())
                .email(user.getEmail())
                .description("Renouvellement forcé par admin - plan " + plan.getNomPlan())
                .build();

        PaymentResponseDTO response = paiementClient.initierPaiement(paymentRequest);

        Renouvellement renouvellement = Renouvellement.builder()
                .abonnement(abonnement)
                .paiementId(response.getTransactionId())
                .dateRenouvellement(LocalDateTime.now())
                .prixApplique(plan.getPrix())
                .typeRenouvellement(com.serviceabonnement.enums.TypeRenouvellement.MANUEL)
                .statut(com.serviceabonnement.enums.StatutRenouvellement.EN_ATTENTE)
                .build();
        renouvellementRepository.save(renouvellement);

        eventPublisher.publishRenouvellementEffectue(
                userClient.getUserById(abonnement.getUserId()),
                abonnement,
                "MANUEL"
        );
    }

    @Override
    @Transactional
    public void desactiver(Long abonnementId, int jours) {
        Abonnement abonnement = getAbonnementById(abonnementId);
        PlanAbonnement plan = abonnement.getPlan();

        if (abonnement.getStatut() != StatutAbonnement.ACTIF) {
            throw new RegleMetierException("L'abonnement doit être ACTIF pour pouvoir être désactivé.");
        }

        // Vérification des règles de désactivation du plan
        if (jours > plan.getMaxPeriodeDesactivation()) {
            throw new RegleMetierException(
                    "Durée de désactivation " + jours + " jours dépasse le maximum autorisé ("
                            + plan.getMaxPeriodeDesactivation() + " jours)");
        }

        List<Desactivation> desactivations = desactivationRepository.findByAbonnementId(abonnementId);
        int nbUsees = desactivations.size();
        if (nbUsees >= plan.getMaxDesactivation()) {
            throw new RegleMetierException("Nombre maximum de désactivations atteint (" + plan.getMaxDesactivation() + ").");
        }

        if (!desactivations.isEmpty()) {
            Desactivation lastDesactivation = desactivations.stream()
                    .max(java.util.Comparator.comparing(Desactivation::getDateFinDesactivation))
                    .get();
            
            long daysSinceLast = java.time.temporal.ChronoUnit.DAYS.between(
                    lastDesactivation.getDateFinDesactivation(), LocalDateTime.now());
            
            if (daysSinceLast < plan.getMinJoursEntreDesactivation()) {
                throw new RegleMetierException("Délai minimum entre deux désactivations non respecté (il faut attendre " 
                        + plan.getMinJoursEntreDesactivation() + " jours, actuellement " + daysSinceLast + " jours écoulés).");
            }
        }

        // Logique de pause
        abonnement.setStatut(StatutAbonnement.DESACTIVE);
        // On repousse la date de fin
        abonnement.setDateFin(abonnement.getDateFin().plusDays(jours));

        abonnementRepository.save(abonnement);

        // Historisation
        Desactivation desactivation = Desactivation.builder()
                .abonnement(abonnement)
                .dateDebutDesactivation(LocalDateTime.now())
                .dateFinDesactivation(LocalDateTime.now().plusDays(jours))
                .build();
        desactivationRepository.save(desactivation);

        eventPublisher.publishDesactivationEffectuee(
                userClient.getUserById(abonnement.getUserId()),
                plan.getNomPlan(),
                LocalDateTime.now(),
                LocalDateTime.now().plusDays(jours),
                nbUsees + 1,
                plan.getMaxDesactivation()
        );
    }

    @Override
    @Transactional
    public void suspendre(Long abonnementId, String motif, LocalDateTime dateFinSuspension) {
        Abonnement abonnement = getAbonnementById(abonnementId);
        if (abonnement.getStatut() == StatutAbonnement.ANNULE ||
                abonnement.getStatut() == StatutAbonnement.SUSPENDU) {
            throw new AbonnementStatutInvalideException(
                    "Impossible de suspendre un abonnement au statut : " + abonnement.getStatut());
        }
        abonnement.setStatut(StatutAbonnement.SUSPENDU);
        abonnementRepository.save(abonnement);

        String adminId = "ROLE_ADMIN_G2";
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            adminId = auth.getName();
        }

        SuspensionAdmin suspensionAdmin = SuspensionAdmin.builder()
                .abonnement(abonnement)
                .adminId(adminId)
                .dateDebutSuspension(LocalDateTime.now())
                .dateFinSuspension(dateFinSuspension)
                .motif(motif)
                .build();
        suspensionAdminRepository.save(suspensionAdmin);

        eventPublisher.publishSuspensionEffectuee(
                userClient.getUserById(abonnement.getUserId()),
                abonnement.getPlan().getNomPlan(),
                adminId,
                motif,
                LocalDateTime.now());
    }

    @Override
    @Transactional
    @CircuitBreaker(name = "externalService", fallbackMethod = "annulationFallback")
    @Retry(name = "externalService")
    public void demanderAnnulation(Long abonnementId) {
        Abonnement abonnement = getAbonnementById(abonnementId);
        if (abonnement.getStatut() == StatutAbonnement.ANNULE ||
                abonnement.getStatut() == StatutAbonnement.ANNULATION_EN_COURS ||
                abonnement.getStatut() == StatutAbonnement.EXPIRE) {
            throw new AbonnementStatutInvalideException(
                "L'abonnement est déjà annulé ou en cours d'annulation : " + abonnement.getStatut());
        }

        // Calcul des jours restants avant la fin de l'abonnement
        long joursRestants = 0;
        if (abonnement.getDateFin() != null) {
            joursRestants = java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), abonnement.getDateFin());
        }
        
        Double montantRemboursement = 0.0;

        if (joursRestants >= 3 && abonnement.getDateFin() != null) {
            montantRemboursement = calculerRemboursement(abonnement);
        } else {
            log.info(
                    "Annulation de l'abonnement {} sans remboursement (pas de date de fin ou < 3 jours)",
                    abonnementId);
        }

        if (montantRemboursement > 0) {
            abonnement.setStatut(StatutAbonnement.ANNULATION_EN_COURS);
        } else {
            abonnement.setStatut(StatutAbonnement.ANNULE);
            abonnement.setDateAnnulation(LocalDateTime.now());
        }
        
        abonnement.setDateDemandeAnnulation(LocalDateTime.now());
        abonnementRepository.save(abonnement);

        // Appel G6 pour le remboursement uniquement si le montant est supérieur à 0
        if (montantRemboursement > 0) {
            com.serviceabonnement.dto.external.RefundRequestDTO refundRequest = com.serviceabonnement.dto.external.RefundRequestDTO.builder()
                    .transactionId(abonnement.getPaiementId())
                    .montantRemboursement(montantRemboursement)
                    .motif("Annulation utilisateur - Franchise de 3 jours respectée")
                    .build();
            paiementClient.rembourser(refundRequest);
        }

        eventPublisher.publishAnnulationEffectuee(
                userClient.getUserById(abonnement.getUserId()),
                abonnement,
                montantRemboursement,
                abonnement.getPaiementId()
        );
    }

    private Double calculerRemboursement(Abonnement abonnement) {
        if (abonnement.getDateDebut() == null || abonnement.getDateFin() == null)
            return 0.0;

        java.time.Duration totalDuration = java.time.Duration.between(abonnement.getDateDebut(),
                abonnement.getDateFin());
        java.time.Duration remainingDuration = java.time.Duration.between(LocalDateTime.now(), abonnement.getDateFin());

        long totalDays = totalDuration.toDays();
        long remainingDays = remainingDuration.toDays();

        if (totalDays <= 0 || remainingDays <= 0)
            return 0.0;

        return (abonnement.getPrixPaye() / totalDays) * remainingDays;
    }

    private LocalDateTime calculerDateFin(com.serviceabonnement.enums.DureeOffre duree) {
        return calculerDateFin(duree, LocalDateTime.now());
    }

    private LocalDateTime calculerDateFin(com.serviceabonnement.enums.DureeOffre duree, LocalDateTime startDate) {
        if (startDate == null) {
            startDate = LocalDateTime.now();
        }
        return switch (duree) {
            case HEBDOMADAIRE -> startDate.plusWeeks(1);
            case MENSUEL -> startDate.plusMonths(1);
            case TRIMESTRIEL -> startDate.plusMonths(3);
            case ANNUEL -> startDate.plusYears(1);
        };
    }

    @Override
    @Transactional
    public Abonnement renouvelerManuel(Long abonnementId) {
        Abonnement abonnement = getAbonnementById(abonnementId);
        PlanAbonnement plan = abonnement.getPlan();

        log.info("Renouvellement manuel demandé pour l'abonnement {}", abonnementId);

        UserDTO user = userClient.getUserById(abonnement.getUserId());
        PaymentRequestDTO paymentRequest = PaymentRequestDTO.builder()
                .userId(abonnement.getUserId())
                .sourceType("SUBSCRIPTION")
                .sourceId(abonnementId)
                .amount(plan.getPrix())
                .paymentMethod(getRandomPaymentMethod())
                .email(user.getEmail())
                .description("Renouvellement manuel - plan " + plan.getNomPlan())
                .build();

        PaymentResponseDTO response = paiementClient.initierPaiement(paymentRequest);

        Renouvellement renouvellement = Renouvellement.builder()
                .abonnement(abonnement)
                .paiementId(response.getTransactionId())
                .dateRenouvellement(LocalDateTime.now())
                .prixApplique(plan.getPrix())
                .typeRenouvellement(com.serviceabonnement.enums.TypeRenouvellement.MANUEL)
                .statut(com.serviceabonnement.enums.StatutRenouvellement.EN_ATTENTE)
                .build();
        renouvellementRepository.save(renouvellement);

        eventPublisher.publishRenouvellementEffectue(
                userClient.getUserById(abonnement.getUserId()),
                abonnement,
                "MANUEL"
        );

        return abonnement;
    }

    @Override
    @Transactional
    public Abonnement renouveler(Long abonnementId) {
        Abonnement abonnement = getAbonnementById(abonnementId);
        PlanAbonnement plan = abonnement.getPlan();

        log.info("Renouvellement automatique pour l'abonnement {}", abonnementId);

        UserDTO user = userClient.getUserById(abonnement.getUserId());
        // Initiation Paiement
        PaymentRequestDTO paymentRequest = PaymentRequestDTO.builder()
                .userId(abonnement.getUserId())
                .sourceType("SUBSCRIPTION")
                .sourceId(abonnementId)
                .amount(plan.getPrix())
                .paymentMethod(getRandomPaymentMethod())
                .email(user.getEmail())
                .description("Renouvellement automatique plan " + plan.getNomPlan())
                .build();

        PaymentResponseDTO response = paiementClient.initierPaiement(paymentRequest);

        Renouvellement renouvellement = Renouvellement.builder()
                .abonnement(abonnement)
                .paiementId(response.getTransactionId())
                .dateRenouvellement(LocalDateTime.now())
                .prixApplique(plan.getPrix())
                .typeRenouvellement(com.serviceabonnement.enums.TypeRenouvellement.AUTOMATIQUE)
                .statut(com.serviceabonnement.enums.StatutRenouvellement.EN_ATTENTE)
                .build();
        renouvellementRepository.save(renouvellement);
        
        eventPublisher.publishRenouvellementEffectue(
                userClient.getUserById(abonnement.getUserId()),
                abonnement,
                "AUTOMATIQUE"
        );

        return abonnement;
    }

    @Override
    @Transactional
    public void forcerAnnulation(Long abonnementId, String motif) {
        Abonnement abonnement = getAbonnementById(abonnementId);
        abonnement.setStatut(StatutAbonnement.ANNULE);
        abonnement.setDateAnnulation(LocalDateTime.now());
        abonnementRepository.save(abonnement);

        eventPublisher.publishAnnulationEffectuee(
                userClient.getUserById(abonnement.getUserId()),
                abonnement,
                0.0, // Pas de remboursement automatique sur annulation admin forcée ici
                null);
    }

    @Override
    @Transactional
    public void confirmerPaiement(com.serviceabonnement.dto.external.PaymentCallbackDTO callback) {
        String token = callback.getTransactionToken();

        // --- Branch 1: New subscription payment ---
        java.util.Optional<Abonnement> abonnementOpt = abonnementRepository.findByPaiementId(token);
        if (abonnementOpt.isPresent()) {
            Abonnement abonnement = abonnementOpt.get();

            // Idempotency guard — ignore duplicate webhook
            if (abonnement.getStatut() == StatutAbonnement.ACTIF) {
                log.warn("Webhook reçu pour un abonnement déjà actif {}. Ignoré (idempotence).", abonnement.getId());
                return;
            }

            if ("SUCCESS".equals(callback.getStatus())) {
                abonnement.setStatut(StatutAbonnement.ACTIF);
                abonnement.setDateDebut(LocalDateTime.now());
                abonnement.setDateFin(calculerDateFin(abonnement.getPlan().getDuree()));

                // Isolated REQUIRES_NEW transaction — refund failure for old plan
                // must never roll back the new plan's activation.
                self.annulerAnciensAbonnementsActifsEnConflit(abonnement);

                log.info("Abonnement {} activé avec succès (Token: {})", abonnement.getId(), token);
                sendToAnalyse(abonnement, "SOUSCRIPTION_CONFIRMEE", abonnement.getPrixPaye());
                eventPublisher.publishConfirmationSouscription(
                        userClient.getUserById(abonnement.getUserId()), abonnement);
            } else {
                abonnement.setStatut(StatutAbonnement.ECHEC_PAIEMENT);
                log.warn("Le paiement a échoué pour l'abonnement {} : {}", abonnement.getId(), callback.getMessage());
            }

            abonnementRepository.save(abonnement);
            return;
        }

        // --- Branch 2: Renewal payment ---
        // Token belongs to a Renouvellement, not a new subscription.
        Renouvellement renouvellement = renouvellementRepository.findByPaiementId(token)
                .orElseThrow(() -> new AbonnementNotFoundException(
                        "Aucun abonnement ou renouvellement associé au token " + token));

        // Idempotency guard for renewals
        if (renouvellement.getStatut() == com.serviceabonnement.enums.StatutRenouvellement.SUCCES) {
            log.warn("Webhook reçu pour un renouvellement déjà traité {}. Ignoré (idempotence).", renouvellement.getId());
            return;
        }

        Abonnement abonnement = renouvellement.getAbonnement();
        if ("SUCCESS".equals(callback.getStatus())) {
            abonnement.setDateFin(calculerDateFin(abonnement.getPlan().getDuree(), abonnement.getDateFin()));
            if (abonnement.getStatut() != StatutAbonnement.SUSPENDU) {
                abonnement.setStatut(StatutAbonnement.ACTIF);
            }
            renouvellement.setStatut(com.serviceabonnement.enums.StatutRenouvellement.SUCCES);
            abonnementRepository.save(abonnement);
            renouvellementRepository.save(renouvellement);
            log.info("Renouvellement {} confirmé. Nouvelle dateFin: {}", renouvellement.getId(), abonnement.getDateFin());
            eventPublisher.publishRenouvellementEffectue(
                    userClient.getUserById(abonnement.getUserId()),
                    abonnement,
                    renouvellement.getTypeRenouvellement().name());
        } else {
            renouvellement.setStatut(com.serviceabonnement.enums.StatutRenouvellement.ECHOUE);
            renouvellementRepository.save(renouvellement);
            log.warn("Paiement de renouvellement échoué pour {} : {}", renouvellement.getId(), callback.getMessage());
        }
    }

    @Override
    @Transactional
    public void confirmerRemboursement(com.serviceabonnement.dto.external.RefundCallbackDTO callback) {
        Abonnement abonnement = abonnementRepository.findByPaiementId(callback.getTransactionId())
                .orElseGet(() -> abonnementRepository.findByRemboursementId(callback.getTransactionId())
                        .orElseThrow(() -> new AbonnementNotFoundException(
                                "Aucun abonnement associé à la transaction " + callback.getTransactionId())));

        switch (callback.getStatut()) {
            case "REMBOURSE" -> {
                abonnement.setStatut(StatutAbonnement.ANNULE);
                abonnement.setDateAnnulation(LocalDateTime.now());
                log.info("Abonnement {} annulé avec succès (Remboursement effectué)", abonnement.getId());
                sendToAnalyse(abonnement, "ANNULATION_CONFIRMEE", 0.0);
                eventPublisher.publishAnnulationEffectuee(userClient.getUserById(abonnement.getUserId()), abonnement,
                        callback.getMontantRembourse(), callback.getTransactionId());
            }
            case "ECHEC_REMBOURSEMENT" -> {
                abonnement.setStatut(StatutAbonnement.ECHEC_REMBOURSEMENT);
                log.error("Échec définitif du remboursement pour l'abonnement {} : {}", abonnement.getId(),
                        callback.getMotif());
                // Ici on pourrait notifier un administrateur
            }
            case "EN_COURS" -> {
                abonnement.setStatut(StatutAbonnement.ANNULATION_EN_COURS);
                log.info("Remboursement en cours (tentative) pour l'abonnement {}", abonnement.getId());
            }
        }

        abonnementRepository.save(abonnement);
    }

    @Override
    public com.serviceabonnement.dto.external.ActiveSubscriptionResponseDTO verifierAbonnementActif(Long userId) {
        Abonnement actif = getActif(userId);

        if (actif != null) {
            return com.serviceabonnement.dto.external.ActiveSubscriptionResponseDTO.builder()
                    .aUnAbonnementActif(true)
                    .typePlan(actif.getPlan().getDuree().toString())
                    .dateExpiration(actif.getDateFin().toLocalDate().toString())
                    .build();
        }

        return com.serviceabonnement.dto.external.ActiveSubscriptionResponseDTO.builder()
                .aUnAbonnementActif(false)
                .typePlan(null)
                .dateExpiration(null)
                .build();
    }

    private void sendToAnalyse(Abonnement abonnement, String action, Double amount) {
        try {
            // 1. Vérification Admin (G3) - On ne suit pas les admins
            UserDTO user = userClient.getUserById(abonnement.getUserId());
            if (user != null && user.getRoles() != null && user.getRoles().contains("ROLE_ADMIN_G2")) {
                log.info("Analyse ignorée pour l'utilisateur admin_g2 {}", abonnement.getUserId());
                return;
            }

            // 2. Formatage des données
            String planType = mapPlanType(abonnement.getPlan().getDuree(), abonnement.getPlan().getCategorie());
            String formattedAction = mapAction(action);
            String timestamp = java.time.ZonedDateTime.now(java.time.ZoneId.of("UTC"))
                    .format(java.time.format.DateTimeFormatter.ISO_INSTANT);

            // 3. Sauvegarde en base pour le batching (G12)
            AnalytiqueTrace trace = AnalytiqueTrace.builder()
                    .timestamp(timestamp)
                    .userId("USR-" + abonnement.getUserId())
                    .action(formattedAction)
                    .planType(planType)
                    .build();

            analytiqueTraceRepository.save(trace);
            log.info("Trace d'analyse collectée pour l'utilisateur {} - action: {}", trace.getUserId(),
                    formattedAction);

        } catch (Exception e) {
            log.error("Échec de la collecte de la trace d'analyse: {}", e.getMessage());
        }
    }

    private String mapPlanType(com.serviceabonnement.enums.DureeOffre duree,
            com.serviceabonnement.enums.CategorieAbonnement categorie) {
        String d = switch (duree) {
            case HEBDOMADAIRE -> "WEEKLY";
            case MENSUEL -> "MONTHLY";
            case TRIMESTRIEL -> "QUARTERLY";
            case ANNUEL -> "YEARLY";
        };
        String c = switch (categorie) {
            case ROLE_STUDENT -> "STUDENT";
            case ROLE_PASSENGER -> "PASSENGER";
        };
        return d + "_" + c;
    }

    private String mapAction(String action) {
        return switch (action) {
            case "SOUSCRIPTION_INITIALE" -> "created";
            case "SOUSCRIPTION_CONFIRMEE" -> "activated";
            case "ANNULATION_CONFIRMEE" -> "cancelled";
            default -> action.toLowerCase();
        };
    }

    private void validerTransitionAbonnement(Abonnement abonnementActif, PlanAbonnement nouveauPlan) {
        PlanAbonnement planActuel = abonnementActif.getPlan();

        if (planActuel.getIdPlan().equals(nouveauPlan.getIdPlan())) {
            throw new ConflictException("L'utilisateur possede deja un abonnement actif sur ce plan.");
        }

        boolean memeTransport = planActuel.getTransportType() == nouveauPlan.getTransportType();
        boolean memeCategorie = planActuel.getCategorie() == nouveauPlan.getCategorie();
        boolean memeDuree = planActuel.getDuree() == nouveauPlan.getDuree();

        boolean changementDureeMemeCategorie = memeTransport && memeCategorie && !memeDuree;
        boolean changementCategorieMemeOffre = memeTransport && !memeCategorie && memeDuree;

        if (!changementDureeMemeCategorie && !changementCategorieMemeOffre) {
            throw new RegleMetierException(
                    "Changement refuse : il faut garder le meme transport et changer uniquement la duree ou la categorie.");
        }
    }

    /**
     * Bug 2 Fix: @Transactional(REQUIRES_NEW) ensures this runs in its own independent
     * transaction. A failure in the G6 refund call (downgrade scenario) will NOT roll back
     * the outer confirmerPaiement transaction that activated the new subscription.
     *
     * PLAN-CHANGE CANCELLATION ONLY — NOT for regular user cancellations.
     * Refund logic:
     *   - Upgrade (newPrice >= oldRemaining): user already paid reduced price → no refund here.
     *   - Downgrade (newPrice < oldRemaining): user paid 0 → refund = oldRemaining - newPrice.
     *   - Regular Cancellation: handled separately in demanderAnnulation().
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void annulerAnciensAbonnementsActifsEnConflit(Abonnement nouvelAbonnement) {
        List<Abonnement> abonnementsActifs = abonnementRepository.findByUserIdAndStatut(
                nouvelAbonnement.getUserId(),
                StatutAbonnement.ACTIF
        );

        abonnementsActifs.stream()
                .filter(abonnement -> !abonnement.getId().equals(nouvelAbonnement.getId()))
                .filter(abonnement -> abonnement.getPlan().getTransportType() == nouvelAbonnement.getPlan().getTransportType())
                .forEach(abonnement -> {
                    double valeurRestante = calculerRemboursement(abonnement);
                    double prixNouveauPlan = nouvelAbonnement.getPlan().getPrix();

                    // Only refund in downgrade scenario: old remaining > new plan price.
                    // In upgrade, user already paid (newPrice - oldRemaining), so no refund owed.
                    double montantRemboursement = valeurRestante > prixNouveauPlan
                            ? valeurRestante - prixNouveauPlan
                            : 0.0;

                    abonnement.setStatut(StatutAbonnement.ANNULE);
                    abonnement.setDateAnnulation(LocalDateTime.now());
                    abonnementRepository.save(abonnement);
                    log.info("[PLAN-CHANGE] Ancien abonnement {} annulé suite au changement vers l'abonnement {}",
                            abonnement.getId(), nouvelAbonnement.getId());

                    if (montantRemboursement > 0 && abonnement.getPaiementId() != null) {
                        com.serviceabonnement.dto.external.RefundRequestDTO refundRequest = com.serviceabonnement.dto.external.RefundRequestDTO.builder()
                                .transactionId(abonnement.getPaiementId())
                                .montantRemboursement(montantRemboursement)
                                .motif("Remboursement partiel suite à downgrade de plan (différence = oldRemaining - newPrice)")
                                .build();
                        try {
                            paiementClient.rembourser(refundRequest);
                            log.info("[PLAN-CHANGE] Remboursement partiel de {} DH initié pour l'ancien abonnement {}",
                                    montantRemboursement, abonnement.getId());
                        } catch (Exception e) {
                            // Non-fatal: new plan is already activated. Log for manual retry.
                            log.error("[PLAN-CHANGE] Échec du remboursement partiel pour l'abonnement {} (sera retenté manuellement): {}",
                                    abonnement.getId(), e.getMessage());
                        }
                    } else {
                        log.info("[PLAN-CHANGE] Upgrade détecté — aucun remboursement dû pour l'ancien abonnement {}",
                                abonnement.getId());
                    }
                });
    }

    // ─── JWT & Security Helpers ────────────────────────────────────────────────

    private List<String> extractUserRoles() {
        org.springframework.security.core.Authentication auth = 
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                .collect(java.util.stream.Collectors.toList());
        }
        return java.util.List.of();
    }

    private boolean isEligibleForPlan(List<String> userRoles, String planCategory) {
        return userRoles.stream()
            .anyMatch(role -> role.equalsIgnoreCase(planCategory));
    }

    // ─── Fallbacks ────────────────────────────────────────────────────────────

    public Abonnement souscrireFallback(Long userId, Long planId, String email, Throwable t) {
        if (t instanceof BaseException baseException) {
            throw baseException;
        }
        log.error("Fallback souscrire pour l'utilisateur {} - Plan {}. Raison: {}", userId, planId, t.getMessage());
        throw new com.serviceabonnement.exception.ExternalServiceException(
                "Le service de souscription est momentanément indisponible. Veuillez réessayer plus tard.");
    }

    public void annulationFallback(Long abonnementId, Throwable t) {
        log.error("Fallback annulation pour l'abonnement {}. Raison: {}", abonnementId, t.getMessage());
        throw new com.serviceabonnement.exception.ExternalServiceException(
            "Le service d'annulation est momentanément indisponible. Votre demande a été enregistrée mais le remboursement sera traité ultérieurement.");
    }

    private String getRandomPaymentMethod() {
        return java.util.Random.class.getName().equals("java.util.Random")
                ? (new java.util.Random().nextBoolean() ? "CARD" : "MOBILE_MONEY")
                : "CARD";
    }
}

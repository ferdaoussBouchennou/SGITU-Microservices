package com.serviceabonnement.scheduler;

import com.serviceabonnement.client.PaiementClient;
import com.serviceabonnement.client.UtilisateurServiceClient;
import com.serviceabonnement.dto.external.PaymentRequestDTO;
import com.serviceabonnement.dto.external.PaymentResponseDTO;
import com.serviceabonnement.dto.external.RefundRequestDTO;
import com.serviceabonnement.dto.external.UserDTO;
import com.serviceabonnement.entity.Abonnement;
import com.serviceabonnement.enums.StatutAbonnement;
import com.serviceabonnement.producer.SubscriptionEventPublisher;
import com.serviceabonnement.repository.AbonnementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Component
@RequiredArgsConstructor
@Slf4j
public class AbonnementScheduler {

    private final AbonnementRepository abonnementRepository;
    private final PaiementClient paiementClient;
    private final UtilisateurServiceClient userClient;
    private final SubscriptionEventPublisher eventPublisher;

    private static final int MAX_ATTEMPTS = 5;

    /**
     * Tente de traiter les paiements en attente toutes les 5 minutes.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void retryPendingPayments() {
        log.info("Lancement du scheduler de retry des paiements en attente...");
        List<Abonnement> pendingAbonnements = abonnementRepository.findByStatut(StatutAbonnement.EN_ATTENTE_PAIEMENT);

        for (Abonnement abonnement : pendingAbonnements) {
            if (abonnement.getNbTentativesPaiement() >= MAX_ATTEMPTS) {
                log.warn("Nombre maximum de tentatives atteint pour l'abonnement {}. Passage en ECHEC_PAIEMENT.", abonnement.getId());
                abonnement.setStatut(StatutAbonnement.ECHEC_PAIEMENT);
                abonnementRepository.save(abonnement);
                notifyFailure(abonnement, "Échec définitif du paiement après " + MAX_ATTEMPTS + " tentatives.");
                continue;
            }

            try {
                log.info("Tentative de paiement #{} pour l'abonnement {}", abonnement.getNbTentativesPaiement() + 1, abonnement.getId());
                UserDTO user = fetchUserFallback(abonnement.getUserId());
                
                PaymentRequestDTO request = PaymentRequestDTO.builder()
                        .userId(abonnement.getUserId())
                        .sourceType("SUBSCRIPTION")
                        .sourceId(abonnement.getId())
                        .amount(abonnement.getPlan().getPrix())
                        .paymentMethod(getRandomPaymentMethod())
                        .email(user.getEmail())
                        .description("Retry automatique - plan " + abonnement.getPlan().getNomPlan())
                        .build();

                PaymentResponseDTO response = paiementClient.initierPaiement(request);
                abonnement.setPaiementId(response.getTransactionId());
                log.info("Initiation du paiement réussie pour l'abonnement {}", abonnement.getId());
                
            } catch (Exception e) {
                log.error("Nouvel échec de paiement pour l'abonnement {} : {}", abonnement.getId(), e.getMessage());
            } finally {
                abonnement.setNbTentativesPaiement(abonnement.getNbTentativesPaiement() + 1);
                abonnement.setDateDerniereTentativePaiement(LocalDateTime.now());
                abonnementRepository.save(abonnement);
            }
        }
    }

    /**
     * Tente de traiter les remboursements en attente toutes les 5 minutes.
     */
    @Scheduled(fixedRate = 300000)
    public void retryPendingRefunds() {
        log.info("Lancement du scheduler de retry des remboursements en attente...");
        List<Abonnement> refundingAbonnements = abonnementRepository.findByStatut(StatutAbonnement.ANNULATION_EN_COURS);

        for (Abonnement abonnement : refundingAbonnements) {
            if (abonnement.getRemboursementId() != null) continue;

            if (abonnement.getNbTentativesRemb() >= MAX_ATTEMPTS) {
                log.warn("Nombre maximum de tentatives atteint pour le remboursement de l'abonnement {}. Passage en ECHEC_REMBOURSEMENT.", abonnement.getId());
                abonnement.setStatut(StatutAbonnement.ECHEC_REMBOURSEMENT);
                abonnementRepository.save(abonnement);
                notifyRefundFailure(abonnement, "Échec définitif du remboursement après " + MAX_ATTEMPTS + " tentatives.");
                continue;
            }

            try {
                log.info("Tentative de remboursement #{} pour l'abonnement {}", abonnement.getNbTentativesRemb() + 1, abonnement.getId());
                double montant = (abonnement.getPrixPaye() != null) ? abonnement.getPrixPaye() * 0.5 : 0.0;

                RefundRequestDTO request = RefundRequestDTO.builder()
                        .transactionId(abonnement.getPaiementId())
                        .montantRemboursement(montant)
                        .motif("Retry automatique annulation")
                        .build();

                paiementClient.rembourser(request);
                log.info("Appel remboursement réussi pour l'abonnement {}", abonnement.getId());
                
            } catch (Exception e) {
                log.error("Nouvel échec de remboursement pour l'abonnement {} : {}", abonnement.getId(), e.getMessage());
            } finally {
                abonnement.setNbTentativesRemb(abonnement.getNbTentativesRemb() + 1);
                abonnement.setDateDerniereTentativeRemb(LocalDateTime.now());
                abonnementRepository.save(abonnement);
            }
        }
    }

    private UserDTO fetchUserFallback(Long userId) {
        try {
            return userClient.getUserById(userId);
        } catch (Exception e) {
            return UserDTO.builder().id(userId).email("unknown@sgitu.com").build();
        }
    }

    private void notifyFailure(Abonnement abonnement, String motif) {
        try {
            UserDTO user = fetchUserFallback(abonnement.getUserId());
            eventPublisher.publishEchecSouscription(user, abonnement.getPlan().getNomPlan(), motif);
        } catch (Exception e) {
            log.error("Erreur lors de la notification d'échec : {}", e.getMessage());
        }
    }

    private void notifyRefundFailure(Abonnement abonnement, String motif) {
        try {
            UserDTO user = fetchUserFallback(abonnement.getUserId());
            eventPublisher.publishAnnulationEchoue(user, abonnement.getPlan().getNomPlan(), motif);
        } catch (Exception e) {
            log.error("Erreur lors de la notification d'échec remboursement : {}", e.getMessage());
        }
    }

    private String getRandomPaymentMethod() {
        return new Random().nextBoolean() ? "CARD" : "MOBILE_MONEY";
    }
}

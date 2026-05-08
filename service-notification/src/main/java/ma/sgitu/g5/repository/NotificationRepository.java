package ma.sgitu.g5.repository;

import ma.sgitu.g5.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * Déduplication composite : (sourceService + notificationId) = clé unique globale.
     * Deux groupes différents peuvent envoyer le même UUID sans collision.
     */
    boolean existsBySourceServiceAndNotificationId(String sourceService, String notificationId);

    Optional<Notification> findBySourceServiceAndNotificationId(String sourceService, String notificationId);

    /**
     * Recherche par notificationId seul — utilisé pour le retry via l'API.
     * Retourne le premier résultat (cas normal : un seul par sourceService).
     */
    Optional<Notification> findFirstByNotificationId(String notificationId);
}

package com.sgitu.servicegestionincidents.service;

import com.sgitu.servicegestionincidents.client.UtilisateurClient;
import com.sgitu.servicegestionincidents.dto.response.UtilisateurDTO;
import com.sgitu.servicegestionincidents.messaging.event.NotificationEvent;
import com.sgitu.servicegestionincidents.messaging.producer.NotificationProducer;
import com.sgitu.servicegestionincidents.model.entity.Incident;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationProducer notificationProducer;
    private final UtilisateurClient utilisateurClient;

    private NotificationEvent.Recipient buildRecipient(Long userId, String channel) {
        if (userId == null || userId == 0) {
            return NotificationEvent.Recipient.builder()
                    .userId("SYSTEM")
                    .build();
        }

        String email = null;
        String phone = null;

        try {
            UtilisateurDTO user = utilisateurClient.obtenirUtilisateur(userId);
            if (user != null) {
                email = user.getEmail();
                if (user.getProfile() != null) {
                    phone = user.getProfile().getPhone();
                }
            }
        } catch (Exception e) {
            log.warn("Impossible de récupérer les infos de contact pour userId {} depuis G3 : {}", userId, e.getMessage());
            // Fallbacks de résilience en cas de panne de G3
            if ("EMAIL".equals(channel)) email = "fallback@sgitu.ma";
            if ("SMS".equals(channel)) phone = "+212000000000";
        }

        return NotificationEvent.Recipient.builder()
                .userId(userId.toString())
                .email(email)
                .phone(phone)
                .build();
    }

    private NotificationEvent.NotificationEventBuilder buildBaseEvent(String eventType, String channel, String priority, NotificationEvent.Recipient recipient) {
        return NotificationEvent.builder()
                .notificationId(UUID.randomUUID().toString())
                .sourceService("G9_INCIDENTS")
                .eventType(eventType)
                .channel(channel)
                .priority(priority)
                .recipient(recipient);
    }

    @Override
    public void envoyerConfirmation(Incident incident) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("incidentId", incident.getId());
        metadata.put("reference", incident.getReference());
        metadata.put("type", incident.getType().name());
        metadata.put("dateSignalement", incident.getDateSignalement().toString());
        metadata.put("statut", incident.getStatut().name());
        metadata.put("lienSuivi", "https://sgitu.ma/incidents/suivi/" + incident.getReference());

        NotificationEvent.Recipient recipient = buildRecipient(incident.getDeclarantId(), "EMAIL");

        NotificationEvent event = buildBaseEvent("INCIDENT_CONFIRMATION", "EMAIL", "NORMAL", recipient)
                .metadata(metadata)
                .build();

        notificationProducer.envoyerNotification(event);
    }

    @Override
    public void envoyerChangementStatut(Incident incident, String ancienStatut) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reference", incident.getReference());
        metadata.put("ancienStatut", ancienStatut);
        metadata.put("nouveauStatut", incident.getStatut().name());
        metadata.put("dateChangement", java.time.LocalDateTime.now().toString());

        NotificationEvent.Recipient recipient = buildRecipient(incident.getDeclarantId(), "PUSH");

        NotificationEvent event = buildBaseEvent("INCIDENT_STATUS_UPDATED", "PUSH", "NORMAL", recipient)
                .metadata(metadata)
                .build();

        notificationProducer.envoyerNotification(event);
    }

    @Override
    public void envoyerAlerteIoT(Incident incident) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("incidentId", incident.getId());
        metadata.put("reference", incident.getReference());
        metadata.put("type", incident.getType().name());
        metadata.put("source", "IOT");
        metadata.put("localisation", incident.getLocalisation().getLatitude() + "," + incident.getLocalisation().getLongitude());
        metadata.put("dateSignalement", incident.getDateSignalement().toString());
        if (incident.getDescription() != null) {
            metadata.put("description", incident.getDescription());
        }

        NotificationEvent.Recipient recipient = NotificationEvent.Recipient.builder()
                .userId("ROLE_SUPERVISEUR_INCIDENTS")
                .phone("+212600112233") // fallback superviseur
                .build();

        NotificationEvent event = buildBaseEvent("INCIDENT_ALERT", "SMS", "HIGH", recipient)
                .metadata(metadata)
                .build();

        notificationProducer.envoyerNotification(event);
    }

    @Override
    public void envoyerEscalade(Incident incident, String motif) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reference", incident.getReference());
        metadata.put("motifEscalade", motif);
        metadata.put("responsableActuel", incident.getResponsableId() != null ? incident.getResponsableId().toString() : "NON_ASSIGNE");
        metadata.put("type", incident.getType().name());
        metadata.put("gravite", incident.getGravite().name());
        metadata.put("localisation", incident.getLocalisation().getLatitude() + "," + incident.getLocalisation().getLongitude());

        NotificationEvent.Recipient recipient = NotificationEvent.Recipient.builder()
                .userId("ROLE_DIRECTION")
                .email("direction@sgitu.ma") // fallback direction
                .build();

        NotificationEvent event = buildBaseEvent("INCIDENT_ESCALATED", "EMAIL", "HIGH", recipient)
                .metadata(metadata)
                .build();
    
        notificationProducer.envoyerNotification(event);
    }

    @Override
    public void envoyerAssignation(Incident incident) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reference", incident.getReference());
        metadata.put("typePanne", incident.getType().name());
        metadata.put("urgence", incident.getGravite().name());
        metadata.put("localisation", incident.getLocalisation().getLatitude() + "," + incident.getLocalisation().getLongitude());
        if (incident.getDescription() != null) {
            metadata.put("description", incident.getDescription());
        }

        NotificationEvent.Recipient recipient = buildRecipient(incident.getResponsableId(), "SMS");

        NotificationEvent event = buildBaseEvent("INTERVENTION_ASSIGNED", "SMS", "HIGH", recipient)
                .metadata(metadata)
                .build();

        notificationProducer.envoyerNotification(event);
    }
}

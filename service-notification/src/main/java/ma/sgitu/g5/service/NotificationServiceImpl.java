package ma.sgitu.g5.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.sgitu.g5.dto.request.NotificationRequestDTO;
import ma.sgitu.g5.dto.response.NotificationResponseDTO;
import ma.sgitu.g5.dto.response.SendResultDTO;
import ma.sgitu.g5.entity.Notification;
import ma.sgitu.g5.entity.NotificationStatus;
import ma.sgitu.g5.entity.NotificationType;
import ma.sgitu.g5.repository.NotificationRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private final NotificationRepository notificationRepository;
    private final ITemplateService templateService;
    private final IChannelRouter channelRouter;
    private final IRetryService retryService;

    @Override
    @Transactional
    public NotificationResponseDTO send(NotificationRequestDTO dto) {
        String notificationId = dto.getNotificationId();
        if (notificationId == null || notificationId.isBlank()) {
            notificationId = UUID.randomUUID().toString();
        }

        // Normalisation sourceService — garantit unicité même si un groupe envoie "payment" au lieu de "PAYMENT"
        String sourceService = (dto.getSourceService() != null)
                ? dto.getSourceService().toUpperCase().trim()
                : "UNKNOWN";
        dto.setSourceService(sourceService);

        // Déduplication composite : (sourceService + notificationId) = clé unique globale
        // Deux groupes différents avec le même UUID ne génèrent PAS de collision
        if (notificationRepository.existsBySourceServiceAndNotificationId(sourceService, notificationId)) {
            log.warn("[G5] Doublon détecté (id={}  source={}) — ignoré (idempotence)", notificationId, sourceService);
            return buildResponse(notificationId, "ALREADY_QUEUED",
                    "Notification déjà prise en charge", dto.getChannel());
        }

        String message = templateService.hydrateMessage(dto.getEventType(), dto.getMetadata());
        String subject = templateService.hydrateSubject(dto.getEventType(), dto.getMetadata());

        Notification entity = buildEntity(dto, notificationId, subject, message);
        notificationRepository.save(entity);

        dispatchAsync(entity, dto, subject, message);

        log.info("[G5] Notification QUEUED : {} | channel={}", notificationId, dto.getChannel());
        return buildResponse(notificationId, "QUEUED", "Notification prise en charge", dto.getChannel());
    }

    @Override
    @Transactional
    public NotificationResponseDTO retry(String notificationId) {
        Notification entity = notificationRepository.findFirstByNotificationId(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("Notification introuvable : " + notificationId));

        if (entity.getStatus() != NotificationStatus.FAILED) {
            return buildResponse(notificationId, entity.getStatus().name(),
                    "Retry non applicable sur ce statut", entity.getChannel());
        }

        entity.setStatus(NotificationStatus.PENDING);
        entity.setRetryCount(entity.getRetryCount() + 1);
        notificationRepository.save(entity);

        dispatchAsyncFromEntity(entity);
        return buildResponse(notificationId, "QUEUED", "Relance en cours", entity.getChannel());
    }

    @Async
    protected void dispatchAsync(Notification entity, NotificationRequestDTO dto, String subject, String message) {
        try {
            SendResultDTO result = channelRouter.route(dto, subject, message);
            updateStatus(entity, result);
        } catch (Exception ex) {
            handleFailure(entity, ex.getMessage());
        }
    }

    @Async
    protected void dispatchAsyncFromEntity(Notification entity) {
        try {
            NotificationRequestDTO dto = rebuildDtoFromEntity(entity);
            SendResultDTO result = channelRouter.route(dto, entity.getSubject(), entity.getContent());
            updateStatus(entity, result);
        } catch (Exception ex) {
            handleFailure(entity, ex.getMessage());
        }
    }

    @Transactional
    protected void updateStatus(Notification entity, SendResultDTO result) {
        if (result.isSuccess()) {
            entity.setStatus(NotificationStatus.SENT);
            entity.setSentAt(LocalDateTime.now());
            entity.setProvider(result.getProvider());
        } else {
            handleFailure(entity, result.getErrorCode());
        }
        notificationRepository.save(entity);
    }

    @Transactional
    protected void handleFailure(Notification entity, String errorReason) {
        if (retryService.shouldRetry(entity.getRetryCount())) {
            int delay = retryService.nextDelaySeconds(entity.getRetryCount());
            entity.setStatus(NotificationStatus.PENDING);
            entity.setRetryCount(entity.getRetryCount() + 1);
            retryService.scheduleRetry(entity.getNotificationId(), delay);
        } else {
            entity.setStatus(NotificationStatus.FAILED);
            log.error("[G5] Notification FAILED : {} | Raison : {}", entity.getNotificationId(), errorReason);
        }
        notificationRepository.save(entity);
    }

    private Notification buildEntity(NotificationRequestDTO dto, String id, String subject, String msg) {
        Notification n = new Notification();
        n.setNotificationId(id);
        n.setSourceService(dto.getSourceService());
        n.setEventType(dto.getEventType());
        n.setChannel(dto.getChannel());
        
        // Sécurisation de la conversion Enum
        try {
            n.setType(NotificationType.valueOf(dto.getChannel().toUpperCase()));
        } catch (Exception e) {
            log.warn("Canal inconnu {}, repli sur EMAIL", dto.getChannel());
            n.setType(NotificationType.EMAIL);
        }

        n.setPriority(dto.getPriority() != null ? dto.getPriority() : "NORMAL");
        
        if (dto.getRecipient() != null) {
            n.setUserId(dto.getRecipient().getUserId());
            n.setEmail(dto.getRecipient().getEmail());
            n.setPhone(dto.getRecipient().getPhone());
            n.setDeviceToken(dto.getRecipient().getDeviceToken());
            n.setRecipient(resolveRecipientString(dto.getRecipient(), dto.getChannel()));
        }
        
        n.setSubject(subject);
        n.setContent(msg);
        n.setStatus(NotificationStatus.PENDING);
        n.setCreatedAt(LocalDateTime.now());
        return n;
    }

    private String resolveRecipientString(ma.sgitu.g5.dto.request.RecipientDTO r, String channel) {
        String res = switch (channel) {
            case "EMAIL" -> r.getEmail();
            case "SMS" -> r.getPhone();
            case "PUSH" -> r.getDeviceToken();
            default -> "unknown";
        };
        return res != null ? res : "unresolved";
    }

    private NotificationRequestDTO rebuildDtoFromEntity(Notification entity) {
        NotificationRequestDTO dto = new NotificationRequestDTO();
        dto.setNotificationId(entity.getNotificationId());
        dto.setSourceService(entity.getSourceService());
        dto.setEventType(entity.getEventType());
        dto.setChannel(entity.getChannel());
        dto.setPriority(entity.getPriority());
        ma.sgitu.g5.dto.request.RecipientDTO r = new ma.sgitu.g5.dto.request.RecipientDTO();
        r.setUserId(entity.getUserId());
        r.setEmail(entity.getEmail());
        r.setPhone(entity.getPhone());
        r.setDeviceToken(entity.getDeviceToken());
        dto.setRecipient(r);
        return dto;
    }

    private NotificationResponseDTO buildResponse(String id, String status, String msg, String ch) {
        NotificationResponseDTO r = new NotificationResponseDTO();
        r.setNotificationId(id);
        r.setStatus(status);
        r.setMessage(msg);
        r.setChannel(ch);
        r.setQueuedAt(LocalDateTime.now().toString());
        return r;
    }
}
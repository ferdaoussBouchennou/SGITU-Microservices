package ma.sgitu.g5.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.sgitu.g5.dto.request.NotificationRequestDTO;
import ma.sgitu.g5.dto.response.SendResultDTO;
import ma.sgitu.g5.provider.IEmailProvider;
import ma.sgitu.g5.provider.IPushProvider;
import ma.sgitu.g5.provider.ISMSProvider;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChannelRouterImpl implements IChannelRouter {

    private final IEmailProvider emailProvider;
    private final ISMSProvider smsProvider;
    private final IPushProvider pushProvider;

    @Override
    public SendResultDTO route(NotificationRequestDTO dto, String subject, String message) {
        String channel = dto.getChannel();
        log.info("[G5-ROUTER] Canal={} | notificationId={}", channel, dto.getNotificationId());

        return switch (channel) {
            case "EMAIL" -> emailProvider.send(
                    dto.getRecipient().getEmail(), subject, message);
            case "SMS" -> smsProvider.send(
                    dto.getRecipient().getPhone(), message);
            case "PUSH" -> pushProvider.send(
                    dto.getRecipient().getDeviceToken(), message);
            default -> throw new IllegalArgumentException(
                    "[G5-ROUTER] Canal non supporte: " + channel);
        };
    }
}

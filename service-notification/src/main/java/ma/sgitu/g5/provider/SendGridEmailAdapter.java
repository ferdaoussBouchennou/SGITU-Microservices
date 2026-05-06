package ma.sgitu.g5.provider;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ma.sgitu.g5.dto.response.SendResultDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SendGridEmailAdapter implements IEmailProvider {
    private final JavaMailSender mailSender;

    @Value("${spring.mail.from:noreply@sgitu.ma}")
    private String fromAddress;

    @Override
    public SendResultDTO send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            SendResultDTO result = new SendResultDTO();
            result.setSuccess(false);
            result.setErrorCode("EMAIL_MISSING");
            result.setRetryCount(0);
            return result;
        }

        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(fromAddress);
            msg.setTo(to);
            msg.setSubject(subject != null ? subject : "Notification SGITU");
            msg.setText(body != null ? body : "");
            mailSender.send(msg);

            log.info("[G5-EMAIL] Envoye -> {} | sujet: {}", to, subject);

            SendResultDTO result = new SendResultDTO();
            result.setSuccess(true);
            result.setProvider("mailhog-" + UUID.randomUUID());
            result.setRetryCount(0);
            return result;
        } catch (Exception e) {
            log.error("[G5-EMAIL] Echec -> {} | {}", to, e.getMessage());

            SendResultDTO result = new SendResultDTO();
            result.setSuccess(false);
            result.setErrorCode(e.getMessage());
            result.setRetryCount(0);
            return result;
        }
    }
}

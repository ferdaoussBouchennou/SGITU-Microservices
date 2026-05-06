package ma.sgitu.g5.provider;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import ma.sgitu.g5.dto.response.SendResultDTO;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FCMPushAdapter implements IPushProvider {
    @Override
    public SendResultDTO send(String deviceToken, String message) {
        if (deviceToken == null || deviceToken.isBlank()) {
            SendResultDTO result = new SendResultDTO();
            result.setSuccess(false);
            result.setErrorCode("TOKEN_MISSING");
            result.setRetryCount(0);
            return result;
        }

        log.info("[G5-PUSH] Simule -> token={} | {}", deviceToken, message);

        SendResultDTO result = new SendResultDTO();
        result.setSuccess(true);
        result.setProvider("fcm-mock-" + UUID.randomUUID());
        result.setRetryCount(0);
        return result;
    }
}

package ma.sgitu.g5.config;

import java.io.File;
import java.io.FileInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class FirebaseConfig {

    @Value("${firebase.credentials.path:}")
    private String credentialsPath;

    /**
     * Initialise Firebase (FCM) si un fichier de credentials valide est fourni.
     *
     * IMPORTANT : l'absence ou l'invalidité des credentials NE DOIT PAS faire échouer
     * le démarrage du microservice. Dans ce cas, le canal PUSH est simplement désactivé.
     * (Un bind-mount Docker vers un fichier inexistant crée un répertoire : on le détecte ici.)
     */
    @PostConstruct
    public void init() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }

        if (credentialsPath == null || credentialsPath.isBlank()) {
            log.warn("[FCM] FIREBASE_CREDENTIALS_PATH non defini. FCM desactive.");
            return;
        }

        File credentialsFile = new File(credentialsPath);
        if (!credentialsFile.isFile() || !credentialsFile.canRead()) {
            log.warn("[FCM] Credentials introuvables ou illisibles ('{}'). FCM desactive (canal PUSH indisponible).",
                    credentialsPath);
            return;
        }

        try (FileInputStream serviceAccount = new FileInputStream(credentialsFile)) {
            FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();
            FirebaseApp.initializeApp(options);
            log.info("[FCM] Firebase initialise avec les credentials: {}", credentialsPath);
        } catch (Exception e) {
            log.warn("[FCM] Echec initialisation Firebase ({}). FCM desactive.", e.getMessage());
        }
    }
}

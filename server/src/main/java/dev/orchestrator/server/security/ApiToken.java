package dev.orchestrator.server.security;

import dev.orchestrator.server.config.OrchestratorProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The install's API token. Either fixed in application.yml or generated once and
 * kept in {@code <data-dir>/api-token} (owner-only where the file system allows).
 * Null when tokens are turned off.
 */
@Component
public class ApiToken {
    private static final Logger log = LoggerFactory.getLogger(ApiToken.class);

    private final String value;

    public ApiToken(OrchestratorProperties properties) {
        OrchestratorProperties.Security security = properties.security();
        if (security == null || !security.requireToken()) {
            this.value = null;
            log.warn("API token disabled (orchestrator.security.require-token=false): any local process or page can call the API");
            return;
        }
        if (security.token() != null && !security.token().isBlank()) {
            this.value = security.token().trim();
            log.info("API token: fixed value from configuration");
            return;
        }
        this.value = loadOrCreate(properties.tokenFile());
    }

    /** The token, or null when not required. */
    public String value() {
        return value;
    }

    private static String loadOrCreate(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (existing.length() >= 32) {
                    log.info("API token: loaded from {}", file);
                    return existing;
                }
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String token = HexFormat.of().formatHex(bytes);
            Files.createDirectories(file.getParent());
            Files.writeString(file, token + System.lineSeparator(), StandardCharsets.UTF_8);
            restrict(file);
            restrict(file.getParent());
            log.info("API token: generated and saved to {}", file);
            return token;
        } catch (IOException e) {
            throw new IllegalStateException("API 토큰 파일을 만들 수 없습니다: " + file, e);
        }
    }

    private static void restrict(Path path) {
        try {
            if (Files.isDirectory(path)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            } else {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / non-POSIX mounts: the file stays with the user's default ACL
        }
    }
}

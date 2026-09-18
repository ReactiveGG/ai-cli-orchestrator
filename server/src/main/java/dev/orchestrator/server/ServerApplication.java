package dev.orchestrator.server;

import dev.orchestrator.server.config.OrchestratorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(OrchestratorProperties.class)
public class ServerApplication {
    public static void main(String[] args) {
        if (desktopEnabled(args)) {
            int port = port(args);
            if (DesktopLauncher.portInUse(port)) {
                // Second click on the app icon: the server is already running, just show it.
                DesktopLauncher.openBrowser(DesktopLauncher.url(port));
                return;
            }
        }
        SpringApplication.run(ServerApplication.class, args);
    }

    static boolean desktopEnabled(String[] args) {
        return "true".equalsIgnoreCase(argOrProperty(args, "orchestrator.desktop.enabled", "false"));
    }

    static int port(String[] args) {
        try {
            return Integer.parseInt(argOrProperty(args, "server.port", "47120"));
        } catch (NumberFormatException e) {
            return 47120;
        }
    }

    /** {@code --key=value} on the command line beats {@code -Dkey=value} (the jpackage launcher passes both). */
    static String argOrProperty(String[] args, String key, String fallback) {
        for (String arg : args) {
            if (arg.startsWith("--" + key + "=")) {
                return arg.substring(key.length() + 3);
            }
        }
        return System.getProperty(key, fallback);
    }
}

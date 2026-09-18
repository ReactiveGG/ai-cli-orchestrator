package dev.orchestrator.server;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What the packaged desktop app adds on top of the plain server: open the dashboard in
 * the default browser once the server is up, and sit in the system tray with "열기" and
 * "종료" so the window-less process can be found and stopped. Off unless
 * {@code orchestrator.desktop.enabled=true} (the jpackage launcher sets it).
 */
@Component
public class DesktopLauncher {
    private static final Logger log = LoggerFactory.getLogger(DesktopLauncher.class);

    private final ConfigurableApplicationContext context;
    private final boolean enabled;
    private final boolean openBrowser;
    private final int port;

    public DesktopLauncher(ConfigurableApplicationContext context,
                           @Value("${orchestrator.desktop.enabled:false}") boolean enabled,
                           @Value("${orchestrator.desktop.open-browser:true}") boolean openBrowser,
                           @Value("${server.port:47120}") int port) {
        this.context = context;
        this.enabled = enabled;
        this.openBrowser = openBrowser;
        this.port = port;
    }

    public static String url(int port) {
        return "http://localhost:" + port + "/";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        String url = url(port);
        log.info("Desktop mode: {}", url);
        if (openBrowser) {
            openBrowser(url);
        }
        installTray(url);
    }

    /** Java's Desktop API first; on Windows fall back to the shell so it works without a desktop toolkit. */
    public static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            log.debug("Desktop.browse failed: {}", e.toString());
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            List<String> cmd = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", url)
                    : os.contains("mac") ? List.of("open", url) : List.of("xdg-open", url);
            new ProcessBuilder(cmd).start();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not open a browser for {}: {}", url, e.toString());
        }
    }

    private void installTray(String url) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
                log.info("System tray not available; use the browser tab and stop the process to quit");
                return;
            }
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("대시보드 열기");
            open.addActionListener(e -> openBrowser(url));
            MenuItem quit = new MenuItem("종료");
            quit.addActionListener(e -> new Thread(() -> {
                int code = org.springframework.boot.SpringApplication.exit(context, () -> 0);
                System.exit(code);
            }, "desktop-quit").start());
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);
            TrayIcon icon = new TrayIcon(loadTrayImage(), "AI CLI Orchestrator · " + url, menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openBrowser(url));   // double-click
            SystemTray.getSystemTray().add(icon);
        } catch (RuntimeException | LinkageError | java.awt.AWTException e) {
            log.warn("System tray icon not installed: {}", e.toString());
        }
    }

    /** The packaged icon (tray-icon.png, same artwork as the app icon); falls back to a drawn one. */
    static java.awt.Image loadTrayImage() {
        try (java.io.InputStream in = DesktopLauncher.class.getResourceAsStream("/tray-icon.png")) {
            if (in != null) {
                BufferedImage img = javax.imageio.ImageIO.read(in);
                if (img != null) {
                    return img;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("tray-icon.png not usable: {}", e.toString());
        }
        return trayImage();
    }

    /** A drawn icon so the package needs no image asset: rounded blue square with "AI". */
    static BufferedImage trayImage() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x2A, 0x78, 0xD6));
        g.fillRoundRect(0, 0, size, size, 10, 10);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String text = "AI";
        g.drawString(text, (size - fm.stringWidth(text)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }

    /** True when something already answers on the port (a running instance). */
    public static boolean portInUse(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

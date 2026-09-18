package dev.orchestrator.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FolderDialogTest {
    @Test
    void windowsUsesAnStaPowershellFolderBrowserDialog() {
        List<String> cmd = FolderDialog.command(FolderDialog.Backend.WINDOWS, "C:\\dev\\it's mine");
        assertEquals("powershell", cmd.get(0));
        assertTrue(cmd.contains("-STA"), "FolderBrowserDialog needs a single-threaded apartment");
        assertFalse(cmd.contains("-NonInteractive"), "-NonInteractive makes the dialog return Cancel immediately (seen on Windows 11 via WSL interop)");
        String script = cmd.get(cmd.size() - 1);
        assertTrue(script.contains("FolderBrowserDialog"));
        assertTrue(script.contains("SelectedPath = 'C:\\dev\\it''s mine'"), "single quotes doubled: " + script);
        assertFalse(script.contains("$owner"), "an invisible owner window makes the dialog cancel itself at once");
        assertTrue(script.contains("WriteLine($d.SelectedPath)"));

        List<String> wsl = FolderDialog.command(FolderDialog.Backend.WSL, "");
        assertEquals("powershell.exe", wsl.get(0));
        assertFalse(wsl.get(wsl.size() - 1).contains("SelectedPath ="), "no start folder → default location");
    }

    @Test
    void unixBackendsPassTheStartFolder() {
        assertEquals(List.of("zenity", "--file-selection", "--directory", "--title=작업 공간 선택", "--filename=/srv/app/"),
                FolderDialog.command(FolderDialog.Backend.ZENITY, "/srv/app"));
        assertEquals("--getexistingdirectory", FolderDialog.command(FolderDialog.Backend.KDIALOG, "/srv/app").get(1));
        assertTrue(FolderDialog.command(FolderDialog.Backend.MACOS, "/Users/me").get(2).contains("choose folder"));
    }

    @Test
    void noBackendIsAClearError() {
        FolderDialog none = new FolderDialog(FolderDialog.Backend.NONE);
        assertFalse(none.available());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> none.pick("/x"));
        assertTrue(e.getMessage().contains("경로를 직접 입력"), e.getMessage());
    }
}

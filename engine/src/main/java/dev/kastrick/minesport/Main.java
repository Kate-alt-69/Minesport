package dev.kastrick.minesport;

import dev.kastrick.minesport.ui.TestUI;
import javax.swing.*;

/**
 * Minesport — Minecraft world exporter by Kastrick
 * Exports vanilla + modded blocks to OBJ/glTF for Blender.
 */
public class Main {

    public static void main(String[] args) {
        // IPC mode — launched by Minesport's Rust backend worker
        if (args.length > 0 && args[0].equals("--ipc")) {
            IpcMode.run();
            return;
        }

        // Standalone Java UI mode
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            new TestUI().setVisible(true);
        });
    }
}

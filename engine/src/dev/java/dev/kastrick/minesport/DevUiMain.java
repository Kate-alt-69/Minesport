package dev.kastrick.minesport;

import dev.kastrick.minesport.ui.TestUI;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Development-only entry point for the legacy Java diagnostics UI. */
public final class DevUiMain {
    private DevUiMain() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            new TestUI().setVisible(true);
        });
    }
}

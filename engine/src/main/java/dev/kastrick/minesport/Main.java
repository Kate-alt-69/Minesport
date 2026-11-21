package dev.kastrick.minesport;

/**
 * Minesport Java engine entry point.
 * The production desktop application communicates with this process through
 * the line-delimited JSON IPC protocol.
 */
public final class Main {
    public static void main(String[] args) {
        if (args.length > 0 && "--ipc".equals(args[0])) {
            IpcMode.run();
            return;
        }

        System.err.println(
            "Minesport Engine is a backend component. Launch the native desktop app, "
                + "or run ./gradlew runDevUi for the Java developer UI."
        );
    }
}

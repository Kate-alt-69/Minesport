package dev.kastrick.minesport.ui;

import dev.kastrick.minesport.export.GeometryBuilder;
import dev.kastrick.minesport.export.GltfExporter;
import dev.kastrick.minesport.export.ObjExporter;
import dev.kastrick.minesport.region.*;
import dev.kastrick.minesport.resolver.*;
import dev.kastrick.minesport.safety.WorldCopier;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Swing test UI for Minesport.
 * Not the final UI (Go/Fyne handles that) — just for local Java testing.
 */
public class TestUI extends JFrame {

    // ── Fields ────────────────────────────────────────────────────────────────
    private File selectedWorld = null;
    private File tempWorldDir  = null;

    private final JLabel            worldLabel;
    private final JTextField        minXField, minYField, minZField;
    private final JTextField        maxXField, maxYField, maxZField;
    private final JComboBox<String> formatBox;
    private final JButton           selectWorldBtn;
    private final JButton           exportBtn;
    private final JTextArea         logArea;
    private final JProgressBar      progressBar;

    // ── Constructor ───────────────────────────────────────────────────────────
    public TestUI() {
        super("Minesport v0.3");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(750, 620);
        setMinimumSize(new Dimension(600, 500));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        root.setBackground(new Color(40, 40, 45));
        setContentPane(root);

        // ── Top ───────────────────────────────────────────────────────────────
        JPanel topPanel = new JPanel(new BorderLayout(8, 0));
        topPanel.setOpaque(false);

        worldLabel = new JLabel("No world selected");
        worldLabel.setForeground(new Color(180, 180, 200));
        worldLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        selectWorldBtn = makeButton("Select World Folder");
        selectWorldBtn.addActionListener(this::onSelectWorld);

        formatBox = new JComboBox<>(new String[]{"OBJ", "glTF"});
        formatBox.setBackground(new Color(55, 55, 65));
        formatBox.setForeground(new Color(220, 220, 240));
        formatBox.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        topRight.setOpaque(false);
        topRight.add(makeLabel("Format:"));
        topRight.add(formatBox);
        topRight.add(selectWorldBtn);

        topPanel.add(worldLabel, BorderLayout.CENTER);
        topPanel.add(topRight,   BorderLayout.EAST);
        root.add(topPanel, BorderLayout.NORTH);

        // ── Center ────────────────────────────────────────────────────────────
        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.setOpaque(false);

        JPanel boundsPanel = new JPanel(new GridLayout(2, 4, 6, 6));
        boundsPanel.setOpaque(false);
        boundsPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(80, 80, 100)),
            "Export Region (World Coordinates)",
            0, 0, null, new Color(180, 180, 200)
        ));

        minXField = makeField("-64");  minYField = makeField("-64");  minZField = makeField("-64");
        maxXField = makeField("64");   maxYField = makeField("320");  maxZField = makeField("64");

        boundsPanel.add(makeLabel("Min X:")); boundsPanel.add(minXField);
        boundsPanel.add(makeLabel("Min Y:")); boundsPanel.add(minYField);
        boundsPanel.add(makeLabel("Max X:")); boundsPanel.add(maxXField);
        boundsPanel.add(makeLabel("Max Y:")); boundsPanel.add(maxYField);

        JPanel boundsZ = new JPanel(new GridLayout(1, 4, 6, 6));
        boundsZ.setOpaque(false);
        boundsZ.add(makeLabel("Min Z:")); boundsZ.add(minZField);
        boundsZ.add(makeLabel("Max Z:")); boundsZ.add(maxZField);

        JPanel boundsWrapper = new JPanel(new BorderLayout(0, 4));
        boundsWrapper.setOpaque(false);
        boundsWrapper.add(boundsPanel, BorderLayout.CENTER);
        boundsWrapper.add(boundsZ,     BorderLayout.SOUTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(25, 25, 30));
        logArea.setForeground(new Color(140, 220, 140));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setMargin(new Insets(4, 6, 4, 6));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(0, 220));
        logScroll.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 80)));

        centerPanel.add(boundsWrapper, BorderLayout.NORTH);
        centerPanel.add(logScroll,     BorderLayout.CENTER);
        root.add(centerPanel, BorderLayout.CENTER);

        // ── Bottom ────────────────────────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 0));
        bottomPanel.setOpaque(false);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");

        exportBtn = makeButton("Export");
        exportBtn.setEnabled(false);
        exportBtn.addActionListener(this::onExport);

        bottomPanel.add(progressBar, BorderLayout.CENTER);
        bottomPanel.add(exportBtn,   BorderLayout.EAST);
        root.add(bottomPanel, BorderLayout.SOUTH);

        log("Minesport v0.3 ready.");
        log("Select a Minecraft world folder to begin.");
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void onSelectWorld(ActionEvent e) {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Select Minecraft World Folder");

        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            File[] candidates = {
                new File(appdata, "FreesmLauncher/instances"),
                new File(appdata, ".minecraft/saves")
            };
            for (File c : candidates) {
                if (c.exists()) { fc.setCurrentDirectory(c); break; }
            }
        }

        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File chosen = fc.getSelectedFile();
        if (!new File(chosen, "level.dat").exists()) {
            JOptionPane.showMessageDialog(this,
                "That doesn't look like a Minecraft world folder.\n(level.dat not found)",
                "Invalid World", JOptionPane.ERROR_MESSAGE);
            return;
        }

        selectedWorld = chosen;
        worldLabel.setText(chosen.getName() + "  (" + chosen.getAbsolutePath() + ")");
        worldLabel.setForeground(new Color(140, 220, 140));
        exportBtn.setEnabled(true);
        log("World selected: " + chosen.getName());
    }

    private void onExport(ActionEvent e) {
        if (selectedWorld == null) return;

        exportBtn.setEnabled(false);
        selectWorldBtn.setEnabled(false);
        formatBox.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setString("0%");

        final int    minX   = parseInt(minXField.getText(), -64);
        final int    minY   = parseInt(minYField.getText(), -64);
        final int    minZ   = parseInt(minZField.getText(), -64);
        final int    maxX   = parseInt(maxXField.getText(),  64);
        final int    maxY   = parseInt(maxYField.getText(), 320);
        final int    maxZ   = parseInt(maxZField.getText(),  64);
        final String format = (String) formatBox.getSelectedItem();

        SwingWorker<List<BlockData>, String> worker = new SwingWorker<>() {

            @Override
            protected List<BlockData> doInBackground() throws Exception {

                // ── 1. Safe temp copy ─────────────────────────────────────────
                publish("Creating safe temp copy of world...");
                setProgress(5);
                tempWorldDir = WorldCopier.copyToTemp(selectedWorld, msg -> publish(msg));
                publish("Temp copy ready.");
                setProgress(15);

                // ── 2. Scan region files ──────────────────────────────────────
                File regionDir = new File(tempWorldDir, "region");
                if (!regionDir.exists()) throw new IOException("No region folder found in world!");

                File[] mcaFiles = regionDir.listFiles((d, n) -> n.endsWith(".mca"));
                if (mcaFiles == null || mcaFiles.length == 0)
                    throw new IOException("No .mca region files found!");

                publish("Found " + mcaFiles.length + " region file(s).");

                var allBlocks = new java.util.ArrayList<BlockData>();
                for (int fi = 0; fi < mcaFiles.length; fi++) {
                    File mca = mcaFiles[fi];
                    publish("Reading: " + mca.getName());
                    List<BlockData> chunk = RegionReader.readRegion(
                        mca, minX, minY, minZ, maxX, maxY, maxZ, (d, t, m) -> {});
                    allBlocks.addAll(chunk);
                    setProgress(15 + (int)((fi + 1.0) / mcaFiles.length * 30));
                    publish("  → " + chunk.size() + " blocks");
                }
                publish("Total blocks collected: " + allBlocks.size());
                setProgress(48);

                // ── 3. Multipart pass ─────────────────────────────────────────
                publish("Resolving multipart connections...");
                MultipartResolver.resolve(allBlocks);
                long mpCount = allBlocks.stream().filter(b -> b.isMultipart).count();
                publish("Multipart blocks resolved: " + mpCount);
                setProgress(52);

                // Block summary
                var summary = new java.util.TreeMap<String, Integer>();
                for (BlockData b : allBlocks) summary.merge(b.blockId, 1, Integer::sum);
                long vanilla = allBlocks.stream().filter(b ->  b.blockId.startsWith("minecraft:")).count();
                long modded  = allBlocks.size() - vanilla;
                publish("─── Block summary (top 25) ───");
                publish("  Vanilla: " + vanilla + "   Modded: " + modded);
                summary.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(25)
                    .forEach(entry -> {
                        String tag = entry.getKey().startsWith("minecraft:") ? "" : " [MOD]";
                        publish("  " + entry.getKey() + tag + " × " + entry.getValue());
                    });

                // ── 4. Build resolver chain ───────────────────────────────────
                publish("Setting up asset resolvers...");
                var chain = new ResolverChain();

                // Read version from level.dat
                String mcVersion = "1.21.10";
                try {
                    var nbtRoot = dev.kastrick.minesport.nbt.NbtReader.readGzip(
                        new File(tempWorldDir, "level.dat"));
                    if (nbtRoot.has("Data")) {
                        try {
                            mcVersion = nbtRoot.getCompound("Data")
                                               .getCompound("Version")
                                               .getString("Name", "1.21.10");
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ex) {
                    publish("[WARN] Could not read level.dat version, using: " + mcVersion);
                }
                publish("Minecraft version: " + mcVersion);

                // Vanilla
                File mcJar = VanillaResolver.findMinecraftJar(mcVersion);
                if (mcJar != null && mcJar.exists()) {
                    chain.addResolver(new VanillaResolver(mcJar));
                    publish("Vanilla resolver: " + mcJar.getName());
                } else {
                    publish("[WARN] minecraft.jar not found — vanilla blocks will be fallback cubes.");
                }

                // Fabric mods
                ModsLocator.LocatedMods located = ModsLocator.locate(selectedWorld);
                File modsFolder = null;
                if (located != null) {
                    modsFolder = located.modsFolder();
                    publish("Mods folder: " + modsFolder.getAbsolutePath());
                    publish("Loader: " + located.loaderType());
                } else {
                    for (File c : ModsLocator.candidatePaths(mcVersion)) {
                        if (c.exists()) { modsFolder = c; break; }
                    }
                    if (modsFolder != null)
                        publish("Mods folder (fallback): " + modsFolder.getAbsolutePath());
                }

                if (modsFolder != null) {
                    FabricResolver fab = FabricResolver.load(modsFolder, msg -> publish(msg));
                    if (!fab.getNamespaces().isEmpty()) {
                        chain.addResolver(fab);
                        publish("─── Detected mods ───");
                        for (var mod : fab.getDetectedMods())
                            publish("  " + mod.modId() + " v" + mod.version() + " — " + mod.name());
                        publish("Namespaces: " + fab.getNamespaces());
                    }
                } else {
                    publish("[WARN] No mods folder — modded blocks will be fallback cubes.");
                }

                publish("Resolvers ready: " + chain.size());
                setProgress(65);

                // ── 5. Export ─────────────────────────────────────────────────
                var geoBuilder = new GeometryBuilder(chain);
                File outDir    = new File(System.getProperty("user.home"), "Minesport_Exports");
                outDir.mkdirs();

                if ("glTF".equals(format)) {
                    publish("Exporting glTF...");
                    File out = new File(outDir, selectedWorld.getName() + "_export.gltf");
                    new GltfExporter(chain).export(
                        allBlocks, geoBuilder, out,
                        ObjExporter.ExportMode.GROUPED_BY_TYPE, false, (d, t) -> {});
                    publish("glTF → " + out.getAbsolutePath());
                    publish(".bin → " + out.getAbsolutePath().replace(".gltf", ".bin"));
                } else {
                    publish("Exporting OBJ...");
                    File out = new File(outDir, selectedWorld.getName() + "_export.obj");
                    ObjExporter.exportWithGeometry(
                        allBlocks, geoBuilder, out,
                        ObjExporter.ExportMode.GROUPED_BY_TYPE, false, (d, t) -> {});
                    publish("OBJ → " + out.getAbsolutePath());
                }

                setProgress(100);
                return allBlocks;
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                chunks.forEach(TestUI.this::log);
            }

            @Override
            protected void done() {
                try {
                    get();
                    progressBar.setString("Done!");
                    log("─────────────────────────────────────────────");
                    log("Export complete! Check ~/Minesport_Exports/");
                    log("─────────────────────────────────────────────");
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    log("ERROR: " + cause.getMessage());
                    JOptionPane.showMessageDialog(TestUI.this,
                        "Export failed:\n" + cause.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                    progressBar.setString("Failed");
                } finally {
                    exportBtn.setEnabled(true);
                    selectWorldBtn.setEnabled(true);
                    formatBox.setEnabled(true);
                }
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int pct = (Integer) evt.getNewValue();
                progressBar.setValue(pct);
                progressBar.setString(pct + "%");
            }
        });

        worker.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private static JButton makeButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(new Color(70, 130, 180));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setBorder(new EmptyBorder(6, 14, 6, 14));
        return btn;
    }

    private static JTextField makeField(String val) {
        JTextField f = new JTextField(val, 6);
        f.setBackground(new Color(55, 55, 65));
        f.setForeground(new Color(220, 220, 240));
        f.setCaretColor(Color.WHITE);
        f.setFont(new Font("Consolas", Font.PLAIN, 13));
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(90, 90, 110)),
            new EmptyBorder(3, 6, 3, 6)
        ));
        return f;
    }

    private static JLabel makeLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(180, 180, 200));
        l.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        return l;
    }
}

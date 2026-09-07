from pathlib import Path
import sys


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def patch_java() -> None:
    path = Path("engine/src/main/java/dev/kastrick/minesport/export/MtlExporter.java")
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''        File texturesDir = new File(mtlFile.getParent(), "textures");
        texturesDir.mkdirs();''',
        '''        File texturesDir = textureDirectoryFor(mtlFile);
        texturesDir.mkdirs();
        String textureReference = texturesDir.getName();''',
        "per-export OBJ texture directory",
    )
    text = replace_once(
        text,
        'w.println("map_Kd textures/" + pngName);',
        'w.println("map_Kd " + textureReference + "/" + pngName);',
        "OBJ diffuse texture reference",
    )
    text = replace_once(
        text,
        'w.println("map_d textures/" + alphaName);',
        'w.println("map_d " + textureReference + "/" + alphaName);',
        "OBJ alpha texture reference",
    )
    helper = r'''
    static File textureDirectoryFor(File mtlFile) {
        String name = mtlFile == null ? "Minesport_Export.mtl" : mtlFile.getName();
        String baseName = name.replaceFirst("(?i)\\.mtl$", "");
        File parent = mtlFile == null ? null : mtlFile.getParentFile();
        return new File(parent == null ? new File(".") : parent, baseName + "_textures");
    }

'''
    text = replace_once(
        text,
        "    private static boolean hasTransparency(BufferedImage image) {\n",
        helper + "    private static boolean hasTransparency(BufferedImage image) {\n",
        "OBJ texture directory helper",
    )
    path.write_text(text, encoding="utf-8")

    test_path = Path("engine/src/test/java/dev/kastrick/minesport/export/ExportRegressionTest.java")
    tests = test_path.read_text(encoding="utf-8")
    regression = r'''
    @Test
    void objTexturesAreIsolatedPerExportInsteadOfSharedGlobally() throws Exception {
        ResolverChain chain = new ResolverChain();
        chain.addResolver(new FixtureResolver(true));
        GeometryBuilder builder = new GeometryBuilder(chain);
        List<BlockData> blocks = List.of(
            new BlockData(0, 0, 0, "test:fixture", Map.of())
        );
        File first = temp.resolve("first.obj").toFile();
        File second = temp.resolve("second.obj").toFile();

        ObjExporter.exportWithGeometry(
            blocks, builder, first, ObjExporter.ExportMode.ALL_MERGED, false, null
        );
        ObjExporter.exportWithGeometry(
            blocks, builder, second, ObjExporter.ExportMode.ALL_MERGED, false, null
        );

        Path firstTextures = temp.resolve("first_textures");
        Path secondTextures = temp.resolve("second_textures");
        assertTrue(Files.isDirectory(firstTextures));
        assertTrue(Files.isDirectory(secondTextures));
        assertFalse(Files.exists(temp.resolve("textures")));
        String firstMtl = Files.readString(temp.resolve("first.mtl"));
        String secondMtl = Files.readString(temp.resolve("second.mtl"));
        assertTrue(firstMtl.contains("map_Kd first_textures/"));
        assertTrue(secondMtl.contains("map_Kd second_textures/"));
    }

'''
    tests = replace_once(
        tests,
        "    @Test\n    void optimizedObjActuallyWeldsPositionRecords() throws Exception {\n",
        regression + "    @Test\n    void optimizedObjActuallyWeldsPositionRecords() throws Exception {\n",
        "OBJ texture isolation regression",
    )
    test_path.write_text(tests, encoding="utf-8")


def patch_rust() -> None:
    path = Path("desktop/src/app.rs")
    text = path.read_text(encoding="utf-8")
    old = r'''fn resolve_export_collision(path: &Path) -> Option<PathBuf> {
    if !path.exists() {
        return Some(path.to_path_buf());
    }
    let replace = MessageDialog::new()
        .set_level(MessageLevel::Warning)
        .set_title("Replace existing Minesport export?")
        .set_description(format!("{} already exists.\n\nYes: replace it.\nNo: keep it and export with the next available name.", path.display()))
        .set_buttons(MessageButtons::YesNo)
        .show();
'''
    new = r'''fn export_bundle_paths(path: &Path) -> Vec<PathBuf> {
    let mut paths = vec![path.to_path_buf()];
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    let stem = path
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("Minesport_Export");
    let extension = path
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    match extension.as_str() {
        "obj" => {
            paths.push(parent.join(format!("{stem}.mtl")));
            paths.push(parent.join(format!("{stem}.minesport.json")));
            paths.push(parent.join(format!("{stem}_textures")));
        }
        "gltf" => {
            paths.push(parent.join(format!("{stem}.bin")));
            paths.push(parent.join(format!("{stem}.minesport.json")));
        }
        _ => {}
    }
    paths
}

fn export_bundle_conflicts(path: &Path) -> bool {
    export_bundle_paths(path)
        .iter()
        .any(|candidate| fs::symlink_metadata(candidate).is_ok())
}

fn resolve_export_collision(path: &Path) -> Option<PathBuf> {
    if !export_bundle_conflicts(path) {
        return Some(path.to_path_buf());
    }
    let replace = MessageDialog::new()
        .set_level(MessageLevel::Warning)
        .set_title("Replace existing Minesport export?")
        .set_description(format!("{} or one of its companion files already exists.\n\nYes: replace that export bundle.\nNo: keep it and export with the next available name.", path.display()))
        .set_buttons(MessageButtons::YesNo)
        .show();
'''
    text = replace_once(text, old, new, "export bundle collision detection")
    text = replace_once(
        text,
        '''        if !candidate.exists() {
            return candidate;
        }''',
        '''        if !export_bundle_conflicts(&candidate) {
            return candidate;
        }''',
        "next export bundle collision check",
    )

    regression = r'''
    #[test]
    fn export_bundle_collision_detection_includes_companion_files_and_assets() {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let root = std::env::temp_dir().join(format!(
            "minesport-export-bundle-collision-{}-{stamp}",
            std::process::id()
        ));
        fs::create_dir_all(&root).unwrap();

        let obj = root.join("scene.obj");
        fs::write(root.join("scene.mtl"), b"old companion").unwrap();
        assert!(export_bundle_conflicts(&obj));
        assert_eq!(next_export_path(&obj), root.join("scene_2.obj"));
        fs::remove_file(root.join("scene.mtl")).unwrap();
        fs::create_dir_all(root.join("scene_textures")).unwrap();
        assert!(export_bundle_conflicts(&obj));

        let gltf = root.join("model.gltf");
        fs::write(root.join("model.bin"), b"old companion").unwrap();
        assert!(export_bundle_conflicts(&gltf));
        fs::remove_file(root.join("model.bin")).unwrap();
        fs::write(root.join("model.minesport.json"), b"old metadata").unwrap();
        assert!(export_bundle_conflicts(&gltf));

        let clean = root.join("clean.gltf");
        assert!(!export_bundle_conflicts(&clean));
        let _ = fs::remove_dir_all(root);
    }

'''
    text = replace_once(
        text,
        "    #[test]\n    fn only_https_external_urls_are_accepted() {\n",
        regression + "    #[test]\n    fn only_https_external_urls_are_accepted() {\n",
        "export companion collision regression",
    )
    path.write_text(text, encoding="utf-8")


mode = sys.argv[1] if len(sys.argv) > 1 else ""
if mode == "java":
    patch_java()
elif mode == "rust":
    patch_rust()
else:
    raise SystemExit("usage: apply-export-isolation-followups.py <java|rust>")

from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/runtime_worker.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''const GRADLE_WRAPPER_PROPERTIES: &[u8] =
    include_bytes!("../../minesport-bridge-fabric/gradle/wrapper/gradle-wrapper.properties");

const MC_1_21_10: &str = "1.21.10";
const LOADER_1_21_10: &str = "0.18.5";
const FABRIC_API_1_21_10: &str = "0.138.4+1.21.10";
const LOOM_1_21_10: &str = "1.11.7";
''',
    '''const GRADLE_WRAPPER_PROPERTIES: &[u8] =
    include_bytes!("../../minesport-bridge-fabric/gradle/wrapper/gradle-wrapper.properties");
const QUILT_GRADLEW_SH: &[u8] = include_bytes!("../../minesport-bridge-quilt/gradlew");
const QUILT_GRADLEW_BAT: &[u8] = include_bytes!("../../minesport-bridge-quilt/gradlew.bat");
const QUILT_GRADLE_WRAPPER_JAR: &[u8] =
    include_bytes!("../../minesport-bridge-quilt/gradle/wrapper/gradle-wrapper.jar");
const QUILT_GRADLE_WRAPPER_PROPERTIES: &[u8] =
    include_bytes!("../../minesport-bridge-quilt/gradle/wrapper/gradle-wrapper.properties");

const MC_1_21_10: &str = "1.21.10";
const LOADER_1_21_10: &str = "0.18.5";
const FABRIC_API_1_21_10: &str = "0.138.4+1.21.10";
const LOOM_1_21_10: &str = "1.11.7";
const QUILT_LOADER_1_21_10: &str = "0.30.1-beta.3";
const QUILT_LOOM_1_21_10: &str = "1.11.1";
''',
    "Quilt direct-launch constants",
)

text = replace_once(
    text,
    "    let direct_launch = if family == BridgeFamily::Fabric && version == MC_1_21_10 {\n",
    "    let direct_launch = if direct_launch_supported(family, &version) {\n",
    "direct launch eligibility",
)
text = replace_once(
    text,
    '''        Some(ensure_direct_launch_profile(
            &version,
''',
    '''        Some(ensure_direct_launch_profile(
            family,
            &version,
''',
    "direct launch profile family argument",
)
text = replace_once(
    text,
    '''    let app_cds_archive = direct_launch.as_ref().and_then(|manifest| {
        prepare_direct_launch_app_cds_archive(&version, &fingerprint, manifest)
    });
''',
    '''    let app_cds_archive = direct_launch.as_ref().and_then(|manifest| {
        prepare_direct_launch_app_cds_archive(family, &version, &fingerprint, manifest)
    });
''',
    "family-aware AppCDS archive",
)

text = replace_once(
    text,
    '''fn create_workspace<F>(
''',
    '''fn direct_launch_supported(family: BridgeFamily, version: &str) -> bool {
    version == MC_1_21_10 && matches!(family, BridgeFamily::Fabric | BridgeFamily::Quilt)
}

fn create_workspace<F>(
''',
    "direct launch support helper",
)

text = replace_once(
    text,
    '''    let fast_bundled_fabric = family == BridgeFamily::Fabric && version == MC_1_21_10;
    let java = if fast_bundled_fabric {
        create_fast_bundled_workspace(&workspace, version)?;
        21
    } else {
        let prepared = bridge_family::prepare_source(family, version, &workspace, &mut progress)?;
        if family == BridgeFamily::Fabric {
            bridge_java::tooling_java(
                prepared.java,
                prepared.variables.get("loom_version").map(String::as_str),
            )
        } else {
            prepared.java
        }
    };
''',
    '''    let fast_bundled = direct_launch_supported(family, version);
    let java = if fast_bundled {
        create_fast_bundled_workspace(family, &workspace, version)?;
        21
    } else {
        let prepared = bridge_family::prepare_source(family, version, &workspace, &mut progress)?;
        if family == BridgeFamily::Fabric {
            bridge_java::tooling_java(
                prepared.java,
                prepared.variables.get("loom_version").map(String::as_str),
            )
        } else {
            prepared.java
        }
    };
''',
    "fast bundled workspace family",
)

text = replace_once(
    text,
    '''    if fast_bundled_fabric {
        let bridge = runtime::materialize_bundled_bridge()?;
        let target = run_mods.join("minesport_export_worker-fabric-1.21.10.jar");
        link_or_copy(&bridge, &target)
            .with_context(|| format!("stage embedded Bridge {}", bridge.display()))?;
    }
''',
    '''    if fast_bundled {
        let (bridge, target_name) = match family {
            BridgeFamily::Fabric => (
                runtime::materialize_bundled_fabric_bridge()?,
                "minesport_export_worker-fabric-1.21.10.jar",
            ),
            BridgeFamily::Quilt => (
                runtime::materialize_bundled_quilt_bridge()?,
                "minesport_export_worker-quilt-1.21.10.jar",
            ),
            _ => bail!("{} has no bundled direct-launch worker", family.label()),
        };
        let target = run_mods.join(target_name);
        link_or_copy(&bridge, &target)
            .with_context(|| format!("stage embedded {} Bridge {}", family.label(), bridge.display()))?;
    }
''',
    "stage family-specific bundled worker",
)

old_fast_workspace = '''fn create_fast_bundled_workspace(workspace: &Path, version: &str) -> Result<()> {
    let wrapper_dir = workspace.join("gradle").join("wrapper");
    fs::create_dir_all(&wrapper_dir)?;
    write_file(&workspace.join("gradlew"), GRADLEW_SH)?;
    write_file(&workspace.join("gradlew.bat"), GRADLEW_BAT)?;
    write_file(&wrapper_dir.join("gradle-wrapper.jar"), GRADLE_WRAPPER_JAR)?;
    write_file(
        &wrapper_dir.join("gradle-wrapper.properties"),
        GRADLE_WRAPPER_PROPERTIES,
    )?;
    write_file(
        &workspace.join("settings.gradle"),
        SETTINGS_GRADLE.as_bytes(),
    )?;
    write_file(&workspace.join("build.gradle"), BUILD_GRADLE.as_bytes())?;
    write_file(
        &workspace.join("gradle.properties"),
        gradle_properties(version).as_bytes(),
    )?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(workspace.join("gradlew"), fs::Permissions::from_mode(0o755))?;
    }
    Ok(())
}
'''
new_fast_workspace = '''fn create_fast_bundled_workspace(
    family: BridgeFamily,
    workspace: &Path,
    version: &str,
) -> Result<()> {
    let (gradlew_sh, gradlew_bat, wrapper_jar, wrapper_properties, settings, build_gradle) =
        match family {
            BridgeFamily::Fabric => (
                GRADLEW_SH,
                GRADLEW_BAT,
                GRADLE_WRAPPER_JAR,
                GRADLE_WRAPPER_PROPERTIES,
                SETTINGS_GRADLE,
                BUILD_GRADLE,
            ),
            BridgeFamily::Quilt => (
                QUILT_GRADLEW_SH,
                QUILT_GRADLEW_BAT,
                QUILT_GRADLE_WRAPPER_JAR,
                QUILT_GRADLE_WRAPPER_PROPERTIES,
                QUILT_SETTINGS_GRADLE,
                QUILT_BUILD_GRADLE,
            ),
            _ => bail!("{} has no bundled direct-launch workspace", family.label()),
        };

    let wrapper_dir = workspace.join("gradle").join("wrapper");
    fs::create_dir_all(&wrapper_dir)?;
    write_file(&workspace.join("gradlew"), gradlew_sh)?;
    write_file(&workspace.join("gradlew.bat"), gradlew_bat)?;
    write_file(&wrapper_dir.join("gradle-wrapper.jar"), wrapper_jar)?;
    write_file(
        &wrapper_dir.join("gradle-wrapper.properties"),
        wrapper_properties,
    )?;
    write_file(&workspace.join("settings.gradle"), settings.as_bytes())?;
    write_file(&workspace.join("build.gradle"), build_gradle.as_bytes())?;
    let properties = gradle_properties(family, version)?;
    write_file(
        &workspace.join("gradle.properties"),
        properties.as_bytes(),
    )?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(workspace.join("gradlew"), fs::Permissions::from_mode(0o755))?;
    }
    Ok(())
}
'''
text = replace_once(text, old_fast_workspace, new_fast_workspace, "generic fast bundled workspace")

old_profile_dir = '''fn direct_launch_profile_dir(version: &str) -> PathBuf {
    runtime::cache_root()
        .join("bridge-build")
        .join("launch-profiles")
        .join(format!(
            "fabric-{}-loader-{}-api-{}-loom-{}-v{}",
            safe(version),
            safe(LOADER_1_21_10),
            safe(FABRIC_API_1_21_10),
            safe(LOOM_1_21_10),
            DIRECT_LAUNCH_PROFILE_SCHEMA
        ))
}
'''
new_profile_dir = '''fn direct_launch_profile_dir(family: BridgeFamily, version: &str) -> Result<PathBuf> {
    let identity = match family {
        BridgeFamily::Fabric => format!(
            "fabric-{}-loader-{}-api-{}-loom-{}-v{}",
            safe(version),
            safe(LOADER_1_21_10),
            safe(FABRIC_API_1_21_10),
            safe(LOOM_1_21_10),
            DIRECT_LAUNCH_PROFILE_SCHEMA
        ),
        BridgeFamily::Quilt => format!(
            "quilt-{}-loader-{}-api-{}-loom-{}-v{}",
            safe(version),
            safe(QUILT_LOADER_1_21_10),
            safe(FABRIC_API_1_21_10),
            safe(QUILT_LOOM_1_21_10),
            DIRECT_LAUNCH_PROFILE_SCHEMA
        ),
        _ => bail!("{} does not support cached direct launch", family.label()),
    };
    Ok(runtime::cache_root()
        .join("bridge-build")
        .join("launch-profiles")
        .join(identity))
}
'''
text = replace_once(text, old_profile_dir, new_profile_dir, "family-aware direct profile directory")

text = replace_once(
    text,
    '''fn ensure_direct_launch_profile<F>(
    version: &str,
''',
    '''fn ensure_direct_launch_profile<F>(
    family: BridgeFamily,
    version: &str,
''',
    "direct profile resolver signature",
)
text = replace_once(
    text,
    "    let profile = direct_launch_profile_dir(version);\n",
    "    let profile = direct_launch_profile_dir(family, version)?;\n",
    "direct profile resolver path",
)
text = replace_once(
    text,
    "    create_fast_bundled_workspace(&profile, version)?;\n",
    "    create_fast_bundled_workspace(family, &profile, version)?;\n",
    "direct profile synthetic workspace",
)
text = replace_once(
    text,
    '''            "start cached Fabric runtime launch-profile resolver with {}",
            java_home.display()
''',
    '''            "start cached {} runtime launch-profile resolver with {}",
            family.label(),
            java_home.display()
''',
    "direct profile generic spawn context",
)
text = replace_once(
    text,
    '''                return Err(error).context("poll Fabric runtime launch-profile resolver");
''',
    '''                return Err(error).with_context(|| {
                    format!("poll {} runtime launch-profile resolver", family.label())
                });
''',
    "direct profile generic poll context",
)

old_cds = '''fn direct_launch_app_cds_archive_path(version: &str, fingerprint: &str) -> PathBuf {
    direct_launch_profile_dir(version)
        .join("cds")
        .join(format!("{}.jsa", safe(fingerprint)))
}

fn prepare_direct_launch_app_cds_archive(
    version: &str,
    fingerprint: &str,
    manifest: &DirectLaunchManifest,
) -> Option<PathBuf> {
    if !direct_launch_app_cds_eligible(manifest) {
        return None;
    }
    let archive = direct_launch_app_cds_archive_path(version, fingerprint);
    fs::create_dir_all(archive.parent()?).ok()?;
    Some(archive)
}
'''
new_cds = '''fn direct_launch_app_cds_archive_path(
    family: BridgeFamily,
    version: &str,
    fingerprint: &str,
) -> Result<PathBuf> {
    Ok(direct_launch_profile_dir(family, version)?
        .join("cds")
        .join(format!("{}.jsa", safe(fingerprint))))
}

fn prepare_direct_launch_app_cds_archive(
    family: BridgeFamily,
    version: &str,
    fingerprint: &str,
    manifest: &DirectLaunchManifest,
) -> Option<PathBuf> {
    if !direct_launch_app_cds_eligible(manifest) {
        return None;
    }
    let archive = direct_launch_app_cds_archive_path(family, version, fingerprint).ok()?;
    fs::create_dir_all(archive.parent()?).ok()?;
    Some(archive)
}
'''
text = replace_once(text, old_cds, new_cds, "family-aware AppCDS path")

quilt_gradle = r'''
const QUILT_SETTINGS_GRADLE: &str = r#"pluginManagement {
    repositories {
        maven { url 'https://maven.quiltmc.org/repository/release' }
        maven { url 'https://maven.fabricmc.net/' }
        gradlePluginPortal()
    }
}
rootProject.name = 'minesport-runtime-worker-quilt'
"#;

const QUILT_BUILD_GRADLE: &str = r#"import groovy.json.JsonOutput

plugins {
    id 'org.quiltmc.loom' version '1.11.1'
    id 'java'
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    maven { url 'https://maven.quiltmc.org/repository/release' }
    maven { url 'https://maven.fabricmc.net/' }
    mavenCentral()
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()
    modImplementation "org.quiltmc:quilt-loader:${project.quilt_loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
}

tasks.register('minesportResolveRuntime') {
    dependsOn {
        def runTask = tasks.named('runClient').get()
        runTask.taskDependencies.getDependencies(runTask)
    }
    doLast {
        def runTask = tasks.named('runClient').get()
        def inherited = System.getenv()
        def environmentOverrides = runTask.environment
            .findAll { key, value -> inherited[key] != value?.toString() }
            .collectEntries { key, value -> [(key.toString()): value?.toString()] }
        def payload = [
            schema: 1,
            mainClass: runTask.mainClass.get(),
            classpath: runTask.classpath.files.collect { it.absolutePath },
            jvmArgs: runTask.allJvmArgs.collect { it.toString() },
            args: (runTask.args ?: []).collect { it.toString() },
            workingDir: runTask.workingDir.absolutePath,
            environmentOverrides: environmentOverrides,
        ]
        file('minesport-launch.json').text = JsonOutput.toJson(payload)
    }
}
"#;

'''
text = replace_once(
    text,
    "fn gradle_properties(version: &str) -> String {\n",
    quilt_gradle + "fn gradle_properties(family: BridgeFamily, version: &str) -> Result<String> {\n",
    "Quilt synthetic launch project",
)
text = replace_once(
    text,
    '''fn gradle_properties(family: BridgeFamily, version: &str) -> Result<String> {
    format!(
        "minecraft_version={version}\\nloader_version={LOADER_1_21_10}\\nfabric_version={FABRIC_API_1_21_10}\\norg.gradle.daemon=false\\norg.gradle.parallel=false\\norg.gradle.workers.max=1\\norg.gradle.vfs.watch=false\\n"
    )
}
''',
    '''fn gradle_properties(family: BridgeFamily, version: &str) -> Result<String> {
    let properties = match family {
        BridgeFamily::Fabric => format!(
            "minecraft_version={version}\\nloader_version={LOADER_1_21_10}\\nfabric_version={FABRIC_API_1_21_10}\\norg.gradle.daemon=false\\norg.gradle.parallel=false\\norg.gradle.workers.max=1\\norg.gradle.vfs.watch=false\\n"
        ),
        BridgeFamily::Quilt => format!(
            "minecraft_version={version}\\nquilt_loader_version={QUILT_LOADER_1_21_10}\\nfabric_version={FABRIC_API_1_21_10}\\norg.gradle.daemon=false\\norg.gradle.parallel=false\\norg.gradle.workers.max=1\\norg.gradle.vfs.watch=false\\n"
        ),
        _ => bail!("{} has no direct-launch Gradle properties", family.label()),
    };
    Ok(properties)
}
''',
    "family-aware direct Gradle properties body",
)

text = replace_once(
    text,
    '''        assert_eq!(LOOM_1_21_10, "1.11.7");
''',
    '''        assert_eq!(LOOM_1_21_10, "1.11.7");
        assert_eq!(QUILT_LOADER_1_21_10, "0.30.1-beta.3");
        assert_eq!(QUILT_LOOM_1_21_10, "1.11.1");
''',
    "Quilt pinned version tests",
)
text = replace_once(
    text,
    '''        assert!(!GRADLEW_BAT.is_empty());
        assert!(!GRADLE_WRAPPER_JAR.is_empty());
''',
    '''        assert!(!GRADLEW_BAT.is_empty());
        assert!(!GRADLE_WRAPPER_JAR.is_empty());
        assert!(!QUILT_GRADLEW_BAT.is_empty());
        assert!(!QUILT_GRADLE_WRAPPER_JAR.is_empty());
''',
    "Quilt wrapper tests",
)
text = replace_once(
    text,
    '''        let properties = gradle_properties(MC_1_21_10);
        assert!(!properties.contains("org.gradle.jvmargs=-Xmx1536m"));
        assert!(properties.contains("org.gradle.daemon=false"));
        assert!(properties.contains("org.gradle.workers.max=1"));
        assert!(properties.contains("org.gradle.vfs.watch=false"));
''',
    '''        let properties = gradle_properties(BridgeFamily::Fabric, MC_1_21_10).unwrap();
        assert!(!properties.contains("org.gradle.jvmargs=-Xmx1536m"));
        assert!(properties.contains("org.gradle.daemon=false"));
        assert!(properties.contains("org.gradle.workers.max=1"));
        assert!(properties.contains("org.gradle.vfs.watch=false"));

        let quilt = gradle_properties(BridgeFamily::Quilt, MC_1_21_10).unwrap();
        assert!(quilt.contains("quilt_loader_version=0.30.1-beta.3"));
        assert!(quilt.contains("fabric_version=0.138.4+1.21.10"));
''',
    "family-aware Gradle properties tests",
)

extra_tests = r'''
    #[test]
    fn direct_launch_is_limited_to_matching_bundled_workers() {
        assert!(direct_launch_supported(BridgeFamily::Fabric, MC_1_21_10));
        assert!(direct_launch_supported(BridgeFamily::Quilt, MC_1_21_10));
        assert!(!direct_launch_supported(BridgeFamily::Forge, MC_1_21_10));
        assert!(!direct_launch_supported(BridgeFamily::NeoForge, MC_1_21_10));
        assert!(!direct_launch_supported(BridgeFamily::Quilt, "1.21.9"));
    }

    #[test]
    fn quilt_and_fabric_direct_profiles_cannot_alias() {
        let fabric = direct_launch_profile_dir(BridgeFamily::Fabric, MC_1_21_10).unwrap();
        let quilt = direct_launch_profile_dir(BridgeFamily::Quilt, MC_1_21_10).unwrap();
        assert_ne!(fabric, quilt);
        assert!(fabric.to_string_lossy().contains("fabric-1.21.10"));
        assert!(quilt.to_string_lossy().contains("quilt-1.21.10"));
        assert!(QUILT_BUILD_GRADLE.contains("org.quiltmc.loom"));
        assert!(QUILT_BUILD_GRADLE.contains("minesportResolveRuntime"));
    }

'''
text = replace_once(
    text,
    "    #[test]\n    fn capture_watchdog_covers_startup_and_mid_capture_stalls() {\n",
    extra_tests + "    #[test]\n    fn capture_watchdog_covers_startup_and_mid_capture_stalls() {\n",
    "direct launch family tests",
)

path.write_text(text, encoding="utf-8")

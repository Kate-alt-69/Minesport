/// Resolve the JDK used to run Gradle/Loom separately from the Java release
/// targeted by the generated Bridge bytecode.
///
/// Loom 1.17+ requires Java 21 to run even when compiling an older Minecraft
/// target whose mod bytecode must remain Java 17 compatible.
pub fn tooling_java(target_java: u32, loom_version: Option<&str>) -> u32 {
    if target_java >= 21 {
        return target_java;
    }

    let Some(loom_version) = loom_version.map(str::trim).filter(|value| !value.is_empty()) else {
        return target_java;
    };
    let mut parts = loom_version.split('.');
    let major = parts.next().and_then(|value| value.parse::<u32>().ok()).unwrap_or(0);
    let minor = parts.next().and_then(|value| value.parse::<u32>().ok()).unwrap_or(0);

    if major > 1 || (major == 1 && minor >= 17) {
        21
    } else {
        target_java
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modern_loom_uses_java_21_without_changing_java_17_target() {
        assert_eq!(tooling_java(17, Some("1.17.18")), 21);
    }

    #[test]
    fn older_loom_keeps_java_17_tooling() {
        assert_eq!(tooling_java(17, Some("1.2.8")), 17);
        assert_eq!(tooling_java(17, Some("1.6.12")), 17);
    }

    #[test]
    fn newer_targets_keep_their_stronger_java_requirement() {
        assert_eq!(tooling_java(21, Some("1.17.18")), 21);
        assert_eq!(tooling_java(25, Some("1.17.18")), 25);
    }
}

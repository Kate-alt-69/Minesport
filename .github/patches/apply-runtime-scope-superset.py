from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


path = Path("desktop/src/runtime_cache.rs")
text = path.read_text(encoding="utf-8")

text = replace_once(
    text,
    '''fn scope_satisfies(available: &RegistryScope, requested: &RegistryScope) -> bool {
    matches!(available, RegistryScope::Full) || available == requested
}''',
    '''fn scope_satisfies(available: &RegistryScope, requested: &RegistryScope) -> bool {
    match (available, requested) {
        (RegistryScope::Full, _) => true,
        (RegistryScope::Namespaces(_), RegistryScope::Full) => false,
        (RegistryScope::Namespaces(available), RegistryScope::Namespaces(requested)) => requested
            .iter()
            .all(|namespace| available.iter().any(|candidate| candidate == namespace)),
    }
}''',
    "scoped registry superset reuse",
)

old_test = '''        let create = RegistryScope::namespaces(["create"]);
        let mekanism = RegistryScope::namespaces(["mekanism"]);
        assert!(scope_satisfies(&RegistryScope::Full, &RegistryScope::Full));
        assert!(scope_satisfies(&RegistryScope::Full, &create));
        assert!(scope_satisfies(&create, &create));
        assert!(!scope_satisfies(&create, &RegistryScope::Full));
        assert!(!scope_satisfies(&create, &mekanism));'''
new_test = '''        let create = RegistryScope::namespaces(["create"]);
        let mekanism = RegistryScope::namespaces(["mekanism"]);
        let create_and_mekanism = RegistryScope::namespaces(["create", "mekanism"]);
        assert!(scope_satisfies(&RegistryScope::Full, &RegistryScope::Full));
        assert!(scope_satisfies(&RegistryScope::Full, &create));
        assert!(scope_satisfies(&create, &create));
        assert!(scope_satisfies(&create_and_mekanism, &create));
        assert!(scope_satisfies(&create_and_mekanism, &mekanism));
        assert!(!scope_satisfies(&create, &create_and_mekanism));
        assert!(!scope_satisfies(&create, &RegistryScope::Full));
        assert!(!scope_satisfies(&create, &mekanism));'''
text = replace_once(text, old_test, new_test, "scoped registry superset regression")

path.write_text(text, encoding="utf-8")

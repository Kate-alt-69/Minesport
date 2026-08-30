from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match in {path}, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "engine/src/main/java/dev/kastrick/minesport/resolver/AssetResolver.java",
    "public interface AssetResolver {",
    '''public interface AssetResolver extends AutoCloseable {

    /**
     * Release resolver-owned files/resources. Stateless resolvers need no
     * implementation; JAR/ZIP-backed resolvers override this method.
     */
    @Override
    default void close() {}''',
    "AssetResolver lifecycle contract",
)

replace_once(
    "engine/src/main/java/dev/kastrick/minesport/resolver/ResolverChain.java",
    "public class ResolverChain {",
    "public class ResolverChain implements AutoCloseable {",
    "ResolverChain AutoCloseable",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/resolver/ResolverChain.java",
    '''    public List<AssetResolver> getResolvers() { return Collections.unmodifiableList(resolvers); }
    public int size() { return resolvers.size(); }
}''',
    '''    public List<AssetResolver> getResolvers() { return Collections.unmodifiableList(resolvers); }
    public int size() { return resolvers.size(); }

    /**
     * Close every unique resolver exactly once and release the per-request
     * ThreadLocal. Resolver close failures are deliberately isolated so one bad
     * mod JAR cannot prevent the rest of the handles from being released.
     */
    @Override
    public void close() {
        Set<AssetResolver> closed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = resolvers.size() - 1; i >= 0; i--) {
            AssetResolver resolver = resolvers.get(i);
            if (resolver == null || !closed.add(resolver)) continue;
            try {
                resolver.close();
            } catch (Exception error) {
                System.err.println(
                    "[ResolverChain] Failed to close " + resolver.name() + ": " + error.getMessage()
                );
            }
        }
        resolvers.clear();
        missingBlockStates.clear();
        missingModels.clear();
        missingTextures.clear();
        blockStateSources.clear();
        modelSources.clear();
        textureSources.clear();
        if (CURRENT.get() == this) CURRENT.remove();
    }
}''',
    "ResolverChain close implementation",
)

replace_once(
    "engine/src/main/java/dev/kastrick/minesport/resolver/VanillaResolver.java",
    '''    @Override
    public String name() { return "VanillaResolver(" + jarFile.getName() + ")"; }

    public boolean usesSyntheticLegacyModels() { return legacyModelEra; }
''',
    '''    @Override
    public String name() { return "VanillaResolver(" + jarFile.getName() + ")"; }

    @Override
    public void close() {
        VanillaResolver fallback = pistonFallback;
        pistonFallback = null;
        if (fallback != null && fallback != this) {
            fallback.close();
        }
        ZipFile current = zip;
        zip = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) {}
        }
        stateCache.clear();
        modelCache.clear();
        texCache.clear();
        pistonTextureMisses.clear();
    }

    public boolean usesSyntheticLegacyModels() { return legacyModelEra; }
''',
    "VanillaResolver close",
)

# Keep the chain visible to the method finally block. Litematica never creates
# one, so null is the normal value for that direct-state export path.
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''        File tempDir = null;
        File stagedOutput = null;
        try {
''',
    '''        File tempDir = null;
        File stagedOutput = null;
        ResolverChain chain = null;
        try {
''',
    "export resolver chain lifetime declaration",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''            log("Setting up resolvers...");
            var chain = new ResolverChain();
''',
    '''            log("Setting up resolvers...");
            chain = new ResolverChain();
''',
    "export resolver chain assignment",
)
replace_once(
    "engine/src/main/java/dev/kastrick/minesport/IpcMode.java",
    '''        } finally {
            if (stagedOutput != null) {
''',
    '''        } finally {
            if (chain != null) {
                chain.close();
                chain = null;
            }
            if (stagedOutput != null) {
''',
    "export resolver chain cleanup",
)

# Focused lifecycle regression test: close propagates once and CURRENT no longer
# pins the completed chain on the engine request thread.
test = Path("engine/src/test/java/dev/kastrick/minesport/resolver/ResolverChainLifecycleTest.java")
if test.exists():
    raise SystemExit("ResolverChainLifecycleTest.java already exists")
test.write_text('''package dev.kastrick.minesport.resolver;

import dev.kastrick.minesport.model.BlockModel;
import dev.kastrick.minesport.model.BlockState;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResolverChainLifecycleTest {
    @Test
    void closeReleasesUniqueResolversAndClearsCurrentThreadLocal() {
        CountingResolver resolver = new CountingResolver();
        ResolverChain chain = new ResolverChain();
        chain.addResolver(resolver);
        chain.addResolver(resolver);

        assertSame(chain, ResolverChain.current());
        chain.close();

        assertEquals(1, resolver.closeCount);
        assertEquals(0, chain.size());
        assertNull(ResolverChain.current());
    }

    private static final class CountingResolver implements AssetResolver {
        int closeCount;
        @Override public boolean canResolve(String blockId) { return false; }
        @Override public BlockState resolveBlockState(String blockId) { return null; }
        @Override public BlockModel resolveModel(String modelPath) { return null; }
        @Override public BufferedImage resolveTexture(String texturePath) { return null; }
        @Override public String name() { return "CountingResolver"; }
        @Override public void close() { closeCount++; }
    }
}
''', encoding="utf-8")

print("Applied resolver ownership/cleanup and ThreadLocal lifecycle fixes")

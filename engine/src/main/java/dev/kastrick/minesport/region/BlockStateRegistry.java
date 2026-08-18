package dev.kastrick.minesport.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interns logical block states into compact integer IDs.
 *
 * World storage should not repeat a String block ID and a Map<String,String>
 * for every occurrence. A state is described once here, while chunk maps can
 * store only the resulting integer ID.
 */
public final class BlockStateRegistry {
    private final Map<Key, Integer> ids = new HashMap<>();
    private final List<State> states = new ArrayList<>();

    /** Intern a BlockData state and return its stable ID for this registry. */
    public int intern(BlockData block) {
        Key key = Key.from(block);
        Integer existing = ids.get(key);
        if (existing != null) return existing;

        int id = states.size();
        ids.put(key, id);
        states.add(new State(block.blockId, key.stateKey()));
        return id;
    }

    public State get(int id) {
        return states.get(id);
    }

    public int size() {
        return states.size();
    }

    public void clear() {
        ids.clear();
        states.clear();
    }

    public record State(String blockId, String stateKey) {}

    private record Key(String blockId, String stateKey) {
        static Key from(BlockData block) {
            return new Key(block.blockId, canonicalState(block.properties));
        }

        private static String canonicalState(Map<String, String> properties) {
            if (properties == null || properties.isEmpty()) return "";

            List<String> keys = new ArrayList<>(properties.keySet());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder();
            for (String key : keys) {
                if (out.length() > 0) out.append(',');
                out.append(key).append('=').append(properties.get(key));
            }
            return out.toString();
        }
    }
}

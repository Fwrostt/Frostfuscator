package dev.frost.obfuscator.engine;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtectionStats {

    private final Map<String, Long> counters = new ConcurrentHashMap<>();

    public void add(String key, long amount) {
        counters.merge(key, amount, Long::sum);
    }

    public void set(String key, long value) {
        counters.put(key, value);
    }

    public long get(String key) {
        return counters.getOrDefault(key, 0L);
    }

    public Map<String, Long> counters() {
        return Collections.unmodifiableMap(new TreeMap<>(counters));
    }
}

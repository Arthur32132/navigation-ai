package dev.navigationai.runtime;

import java.util.EnumMap;

/** One root intent owns the bot. Child controllers never acquire a second root. */
final class BotControl {
    enum Kind { ACTIVITY, GATHER, NAVIGATION, MINING, COMBAT, MANUAL }
    private final EnumMap<Kind, Runnable> cancellation = new EnumMap<>(Kind.class);
    private long generation;

    void register(Kind kind, Runnable cancel) { cancellation.put(kind, cancel); }

    /** A request started by a cancellation callback wins over its caller. */
    long claim(Kind kind) {
        long request = ++generation;
        for (Kind other : Kind.values()) {
            if (other == kind) continue;
            Runnable cancel = cancellation.get(other);
            if (cancel != null) cancel.run();
            if (request != generation) return -1;
        }
        return request;
    }

    boolean current(long request) { return request == generation; }
    long generation() { return generation; }
}

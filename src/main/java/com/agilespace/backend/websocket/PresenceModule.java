package com.agilespace.backend.websocket;

import java.util.Optional;

/** Módulos com sala em tempo real que têm presença. {@link #path()} é o segmento de {@code /ws/{path}/*} e de {@code /api/presence/{path}/*}. */
public enum PresenceModule {
    POKER("poker"),
    RETRO("retro"),
    BRAINSTORMING("brainstorming"),
    HEALTH_CHECK("health-check"),
    SHOWCASE("showcase");

    private final String path;

    PresenceModule(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static Optional<PresenceModule> fromPath(String path) {
        if (path == null) return Optional.empty();
        for (PresenceModule m : values()) {
            if (m.path.equals(path)) return Optional.of(m);
        }
        return Optional.empty();
    }
}

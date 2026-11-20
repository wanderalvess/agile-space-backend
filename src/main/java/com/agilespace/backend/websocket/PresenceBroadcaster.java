package com.agilespace.backend.websocket;

/** Handler de sala que sabe entregar o {@code PRESENCE_UPDATED} às suas sessões com opt-in (usado pela varredura de zumbis). */
public interface PresenceBroadcaster {

    PresenceModule presenceModule();

    void broadcastPresence(String roomId, PresenceRegistry.Snapshot snapshot);
}

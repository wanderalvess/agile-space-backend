package com.agilespace.backend.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guarda em memória contra duas syncs do mesmo squad rodando ao mesmo tempo — um tick do
 * agendador (SquadSyncScheduler) coincidindo com alguém clicando "Sincronizar" manualmente,
 * ou dois cliques em sequência rápida. Só existe porque o sync agendado introduziu esse
 * risco (antes só um humano por vez clicava o botão, então nunca havia disputa real).
 * Em memória por instância é suficiente aqui — o backend hoje roda como instância única
 * (mesma premissa já assumida pelo broadcast de WebSocket in-memory de Poker/Retro/etc.).
 */
@Component
public class SquadSyncGuard {

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** true se conseguiu reservar o squad pra esta sync; false se já há uma em andamento. */
    public boolean tryAcquire(String squadId) {
        return inFlight.add(squadId);
    }

    public void release(String squadId) {
        inFlight.remove(squadId);
    }
}

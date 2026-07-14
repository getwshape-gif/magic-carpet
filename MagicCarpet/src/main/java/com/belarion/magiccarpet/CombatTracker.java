package com.belarion.magiccarpet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Suit qui est "en combat" (a recemment inflige ou reçu des degats d'un
 * autre joueur) pour interdire /mc pendant ce temps.
 */
public class CombatTracker {

    // Duree pendant laquelle un joueur est considere "en combat" apres un coup, en millisecondes.
    private static final long COMBAT_DURATION_MS = 10_000L;

    private final Map<UUID, Long> lastCombatAt = new HashMap<UUID, Long>();

    public void markInCombat(UUID uuid) {
        lastCombatAt.put(uuid, System.currentTimeMillis());
    }

    public boolean isInCombat(UUID uuid) {
        Long last = lastCombatAt.get(uuid);
        if (last == null) {
            return false;
        }
        return (System.currentTimeMillis() - last) < COMBAT_DURATION_MS;
    }

    public long getRemainingSeconds(UUID uuid) {
        Long last = lastCombatAt.get(uuid);
        if (last == null) {
            return 0L;
        }
        long remainingMs = COMBAT_DURATION_MS - (System.currentTimeMillis() - last);
        if (remainingMs <= 0) {
            return 0L;
        }
        return (remainingMs / 1000L) + 1L;
    }

    public void clear(UUID uuid) {
        lastCombatAt.remove(uuid);
    }
}

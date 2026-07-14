package com.belarion.magiccarpet;

import org.bukkit.Location;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Regroupe les verifications liees aux zones (spawn / warzone).
 *
 * La detection du spawn se fait par simple distance (fiable a 100%, aucune
 * dependance). La detection de la WarZone/SafeZone passe par une integration
 * reflexion avec l'API FactionsUUID/SaberFactions (classes
 * com.massivecraft.factions.Board, FLocation), pour ne pas dependre de son
 * jar a la compilation (il n'est pas publie sur un depot Maven). Si cette
 * integration echoue (version du plugin trop differente), le code bascule
 * automatiquement sur un comportement plus prudent (ne bloque pas la
 * commande) plutot que de planter.
 *
 * NOTE : la detection "ennemi a proximite" a ete retiree volontairement.
 * Le tapis magique se desactive desormais uniquement quand le joueur est
 * reellement attaque (voir CarpetListener#onPvpHit), pas juste parce qu'un
 * autre joueur (potentiellement un allie) se trouve dans les environs.
 */
public final class FactionIntegration {

    // Rayon (en blocs) autour du spawn du monde ou /mc est interdit.
    // Modifie cette valeur si ta zone de spawn est plus grande/petite (ex: 50, 100, 200...).
    public static final double SPAWN_PROTECTION_RADIUS = 60.0D;

    private static boolean warnedZone = false;

    private FactionIntegration() {
    }

    /**
     * True si le joueur est dans le rayon de protection du spawn du monde.
     */
    public static boolean isNearWorldSpawn(Location location) {
        Location spawn = location.getWorld().getSpawnLocation();
        if (!spawn.getWorld().equals(location.getWorld())) {
            return false;
        }
        return spawn.distanceSquared(location) <= (SPAWN_PROTECTION_RADIUS * SPAWN_PROTECTION_RADIUS);
    }

    /**
     * True si le joueur se trouve dans une faction WarZone ou SafeZone (au sens de
     * Faction.isWarZone() / isSafeZone() cote FactionsUUID/SaberFactions). Renvoie
     * false (n'empeche pas la commande) si l'integration echoue, mais previent une
     * fois en console pour que tu puisses le savoir.
     */
    public static boolean isInWarOrSafeZone(Location location) {
        try {
            Class<?> boardClass = Class.forName("com.massivecraft.factions.Board");
            Object board = boardClass.getMethod("getInstance").invoke(null);

            Class<?> flocationClass = Class.forName("com.massivecraft.factions.FLocation");
            Object flocation = flocationClass.getConstructor(Location.class).newInstance(location);

            Method getFactionAt = boardClass.getMethod("getFactionAt", flocationClass);
            Object faction = getFactionAt.invoke(board, flocation);

            if (faction == null) {
                return false;
            }

            Method isWarZone = faction.getClass().getMethod("isWarZone");
            Method isSafeZone = faction.getClass().getMethod("isSafeZone");

            boolean warZone = Boolean.TRUE.equals(isWarZone.invoke(faction));
            boolean safeZone = Boolean.TRUE.equals(isSafeZone.invoke(faction));

            return warZone || safeZone;
        } catch (Throwable t) {
            warnOnceZone();
            return false;
        }
    }

    private static void warnOnceZone() {
        if (!warnedZone) {
            warnedZone = true;
            Logger.getLogger("Minecraft").warning(
                    "[MagicCarpet] Detection WarZone/SafeZone indisponible (integration factions "
                    + "non reconnue). La protection du spawn par distance reste active.");
        }
    }
}

package com.belarion.magiccarpet;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Regroupe les verifications liees aux zones (spawn / warzone) et aux
 * ennemis a proximite.
 *
 * La detection du spawn se fait par simple distance (fiable a 100%, aucune
 * dependance). La detection de la WarZone et des relations "ennemi" passe
 * par une integration best-effort avec SaberFactions via reflexion, pour ne
 * pas dependre de son jar a la compilation (il n'est pas publie sur un
 * depot Maven). Si cette integration echoue (nom de classe/methode
 * different selon la version exacte du plugin installe), le code bascule
 * automatiquement sur un comportement plus prudent plutot que de planter.
 */
public final class FactionIntegration {

    // Rayon (en blocs) autour du spawn du monde ou /mc est interdit.
    // Modifie cette valeur si ta zone de spawn est plus grande/petite.
    public static final double SPAWN_PROTECTION_RADIUS = 150.0D;

    // Rayon (en blocs) de detection d'un ennemi, qui coupe le tapis instantanement.
    public static final double ENEMY_CHECK_RADIUS = 500.0D;

    private static boolean warnedZone = false;
    private static boolean warnedRelation = false;

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
     * True si le joueur se trouve dans une zone nommee "WarZone" ou
     * "SafeZone" cote SaberFactions/Factions. Renvoie false (n'empeche pas
     * la commande) si l'integration echoue, mais previent une fois en
     * console pour que tu puisses le savoir.
     */
    public static boolean isInWarOrSafeZone(Location location) {
        try {
            Class<?> boardCollClass = Class.forName("com.massivecraft.factions.entity.BoardColl");
            Object boardColl = boardCollClass.getMethod("get").invoke(null);

            Class<?> flocationClass = Class.forName("com.massivecraft.factions.util.FLocation");
            Object flocation = flocationClass.getConstructor(Location.class).newInstance(location);

            Method getFactionAt = boardCollClass.getMethod("getFactionAt", flocationClass);
            Object faction = getFactionAt.invoke(boardColl, flocation);

            if (faction == null) {
                return false;
            }

            Method getNameMethod = faction.getClass().getMethod("getName");
            String name = (String) getNameMethod.invoke(faction);

            return name != null && (name.equalsIgnoreCase("WarZone") || name.equalsIgnoreCase("SafeZone"));
        } catch (Throwable t) {
            warnOnceZone();
            return false;
        }
    }

    /**
     * True si un ennemi se trouve dans le rayon donne autour du joueur.
     * Si l'integration avec le systeme de factions echoue, bascule en mode
     * prudent : n'importe quel autre joueur dans le rayon est alors
     * considere comme un risque (mieux vaut couper le tapis a tort qu'a
     * raison sur un serveur PvP).
     */
    public static boolean hasEnemyNearby(Player player, double radius) {
        double radiusSquared = radius * radius;

        Object myMPlayer = null;
        boolean factionsAvailable = true;
        Class<?> mplayerClass = null;

        try {
            mplayerClass = Class.forName("com.massivecraft.factions.entity.MPlayer");
            Method get = mplayerClass.getMethod("get", Object.class);
            myMPlayer = get.invoke(null, player);
        } catch (Throwable t) {
            factionsAvailable = false;
            warnOnceRelation();
        }

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            if (!other.getWorld().equals(player.getWorld())) {
                continue;
            }
            if (other.getLocation().distanceSquared(player.getLocation()) > radiusSquared) {
                continue;
            }

            if (!factionsAvailable) {
                // Mode de secours : tout autre joueur proche est traite comme un risque.
                return true;
            }

            try {
                Method get = mplayerClass.getMethod("get", Object.class);
                Object otherMPlayer = get.invoke(null, other);

                Method getRelationTo = mplayerClass.getMethod("getRelationTo", mplayerClass);
                Object relation = getRelationTo.invoke(myMPlayer, otherMPlayer);

                if (relation != null && "ENEMY".equalsIgnoreCase(relation.toString())) {
                    return true;
                }
            } catch (Throwable t) {
                // Si la verification precise echoue pour ce joueur precis, on reste prudent.
                return true;
            }
        }

        return false;
    }

    private static void warnOnceZone() {
        if (!warnedZone) {
            warnedZone = true;
            Logger.getLogger("Minecraft").warning(
                    "[MagicCarpet] Detection WarZone/SafeZone indisponible (integration SaberFactions "
                    + "non reconnue). La protection du spawn par distance reste active.");
        }
    }

    private static void warnOnceRelation() {
        if (!warnedRelation) {
            warnedRelation = true;
            Logger.getLogger("Minecraft").warning(
                    "[MagicCarpet] Detection des relations de faction indisponible. "
                    + "Mode de secours active : tout joueur a moins de "
                    + (int) ENEMY_CHECK_RADIUS + " blocs coupera le tapis.");
        }
    }
}

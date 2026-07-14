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
    // Modifie cette valeur si ta zone de spawn est plus grande/petite (ex: 50, 100, 200...).
    public static final double SPAWN_PROTECTION_RADIUS = 60.0D;

    // Rayon (en blocs) de detection d'un ennemi, qui coupe le tapis instantanement.
    public static final double ENEMY_CHECK_RADIUS = 200.0D;

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
     * True si un ennemi (relation "ENEMY" cote SaberFactions) se trouve dans le rayon
     * donne autour du joueur. Les allies, membres de la meme faction, neutres, etc. ne
     * declenchent JAMAIS la coupure : seule une relation explicitement "ENEMY" compte.
     *
     * Si l'integration avec le systeme de factions n'est pas trouvee du tout (classe
     * introuvable : le plugin de factions n'est probablement pas installe), on bascule
     * en mode prudent ou n'importe quel autre joueur dans le rayon est considere comme un
     * risque. En revanche, si la classe de base existe mais qu'une verification precise
     * echoue pour un joueur en particulier (methode introuvable pour cette version exacte
     * du plugin, etc.), on NE coupe PAS le tapis pour ce joueur : mieux vaut ne pas gener
     * un allie que de le traiter a tort comme un ennemi.
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
                // Aucune integration de factions detectee du tout : mode de secours.
                return true;
            }

            if (isEnemyRelation(mplayerClass, myMPlayer, other)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Verifie precisement si "other" est en relation ENEMY avec le joueur, via reflexion.
     * Toute incapacite a determiner la relation (methode absente, exception...) renvoie
     * false plutot que true, pour ne jamais bloquer un allie a cause d'une reflexion
     * qui ne correspond pas exactement a la version du plugin de factions installee.
     */
    private static boolean isEnemyRelation(Class<?> mplayerClass, Object myMPlayer, Player other) {
        try {
            Method get = mplayerClass.getMethod("get", Object.class);
            Object otherMPlayer = get.invoke(null, other);

            Method getRelationTo = mplayerClass.getMethod("getRelationTo", mplayerClass);
            Object relation = getRelationTo.invoke(myMPlayer, otherMPlayer);

            if (relation == null) {
                return false;
            }

            String relationName;
            try {
                // Pour un enum (Rel.ENEMY, Rel.ALLY, Rel.MEMBER...), name() donne le nom
                // exact de la constante, plus fiable que toString() qui peut etre redefini
                // (couleurs, libelle affiche, etc.).
                Method nameMethod = relation.getClass().getMethod("name");
                relationName = (String) nameMethod.invoke(relation);
            } catch (Throwable t) {
                relationName = relation.toString();
            }

            return relationName != null && relationName.equalsIgnoreCase("ENEMY");
        } catch (Throwable t) {
            // Verification impossible pour ce joueur precis : on ne le traite PAS comme
            // un ennemi (evite les faux positifs sur des allies).
            return false;
        }
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

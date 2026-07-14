package com.belarion.magiccarpet;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gere toutes les sessions actives de tapis magique.
 * Un joueur = au maximum une session active a la fois.
 */
public class CarpetManager {

    // Delai (en ticks) entre chaque mise a jour du tapis (suivi + saut/sneak). 2 ticks = fluide.
    private static final long TICK_INTERVAL = 2L;

    // On ne verifie la presence d'un ennemi que toutes les X executions du tick ci-dessus,
    // pour ne pas scanner tous les joueurs en ligne 10 fois par seconde inutilement.
    // 5 * 2 ticks = verification toutes les 0.5 secondes, largement assez pour etre "instantane".
    private static final int ENEMY_CHECK_EVERY_N_TICKS = 5;

    // Vitesse Y a partir de laquelle on considere que le joueur a saute.
    private static final double JUMP_VELOCITY_THRESHOLD = 0.2D;

    private final MagicCarpetPlugin plugin;
    private final Map<UUID, CarpetSession> sessions = new HashMap<UUID, CarpetSession>();

    // Joueurs qui ne doivent PAS prendre de degats de chute a leur prochain atterrissage
    // (utilise quand on retire le tapis alors que le joueur est en l'air).
    private final Set<UUID> fallImmune = new HashSet<UUID>();

    public CarpetManager(MagicCarpetPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hasCarpet(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public boolean isFallImmune(UUID uuid) {
        return fallImmune.contains(uuid);
    }

    public void clearFallImmune(UUID uuid) {
        fallImmune.remove(uuid);
    }

    /**
     * True si le joueur a un tapis actif et que le teleport en cours est notre propre
     * ajustement de hauteur (a ignorer par l'ecouteur de teleportation).
     */
    public boolean isInternalTeleport(UUID uuid) {
        CarpetSession session = sessions.get(uuid);
        return session != null && session.isInternalTeleport();
    }

    /**
     * Active le tapis magique sous les pieds du joueur.
     * Les verifications (zone, combat) doivent avoir deja ete faites par l'appelant (CarpetCommand).
     */
    public void createCarpet(Player player) {
        if (hasCarpet(player)) {
            return;
        }

        Location loc = player.getLocation();
        int platformY = loc.getBlockY() - 1;

        final CarpetSession session = new CarpetSession(player.getUniqueId(), platformY);
        sessions.put(player.getUniqueId(), session);

        // Place la plateforme initiale et fait tenir le joueur pile dessus
        placePlatform(session, loc.getBlockX(), loc.getBlockZ(), player.getWorld().getName());
        snapPlayerOnPlatform(player, session);

        // Boucle de mise a jour: suit le joueur, gere saut/sneak, ennemi proche, zone protegee
        BukkitTask task = new BukkitRunnable() {
            private int tickCount = 0;

            @Override
            public void run() {
                tickCount++;
                tick(player, session, tickCount % ENEMY_CHECK_EVERY_N_TICKS == 0);
            }
        }.runTaskTimer(plugin, TICK_INTERVAL, TICK_INTERVAL);

        session.setTask(task);
    }

    /**
     * Coupe le tapis magique suite a la commande /mc. Si le joueur est en l'air, il ne
     * prendra pas de degats de chute a son prochain atterrissage.
     */
    public void removeCarpet(Player player) {
        CarpetSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (session.getTask() != null) {
            session.getTask().cancel();
        }

        restoreBlocks(session);

        if (!player.isOnGround()) {
            fallImmune.add(player.getUniqueId());
        }
    }

    /**
     * Coupe le tapis suite a une vraie teleportation externe (/spawn, /tpa, /home, etc.).
     */
    public void removeDueToTeleport(Player player) {
        CarpetSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        forceRemoveCarpet(player, session, "Tapis magique desactive : teleportation detectee.");
    }

    /**
     * Coupe le tapis instantanement suite a une detection automatique (ennemi proche, zone
     * protegee, teleportation) et previent le joueur. Applique aussi l'immunite de chute.
     */
    private void forceRemoveCarpet(Player player, CarpetSession session, String reasonMessage) {
        sessions.remove(player.getUniqueId());

        if (session.getTask() != null) {
            session.getTask().cancel();
        }

        restoreBlocks(session);

        if (!player.isOnGround()) {
            fallImmune.add(player.getUniqueId());
        }

        player.sendMessage(ChatColor.RED + reasonMessage);
    }

    /**
     * Retire proprement tous les tapis actifs (utilise a la desactivation du plugin
     * ou a la deconnexion d'un joueur).
     */
    public void disableAll() {
        for (CarpetSession session : new HashMap<UUID, CarpetSession>(sessions).values()) {
            if (session.getTask() != null) {
                session.getTask().cancel();
            }
            restoreBlocks(session);
        }
        sessions.clear();
    }

    public void handleQuit(UUID uuid) {
        CarpetSession session = sessions.remove(uuid);
        if (session != null) {
            if (session.getTask() != null) {
                session.getTask().cancel();
            }
            restoreBlocks(session);
        }
        fallImmune.remove(uuid);
    }

    // ------------------------------------------------------------------
    // Logique interne
    // ------------------------------------------------------------------

    private void tick(Player player, CarpetSession session, boolean checkSafety) {
        if (!player.isOnline() || player.isDead()) {
            forceRemoveCarpet(player, session, "Tapis magique desactive.");
            return;
        }

        // --- Verifications de securite: ennemi proche ou zone protegee ---
        if (checkSafety) {
            if (FactionIntegration.hasEnemyNearby(player, FactionIntegration.ENEMY_CHECK_RADIUS)) {
                forceRemoveCarpet(player, session,
                        "Un ennemi est trop proche (moins de " + (int) FactionIntegration.ENEMY_CHECK_RADIUS
                        + " blocs) - tapis magique desactive automatiquement !");
                return;
            }
            if (FactionIntegration.isInWarOrSafeZone(player.getLocation())
                    || FactionIntegration.isNearWorldSpawn(player.getLocation())) {
                forceRemoveCarpet(player, session,
                        "Tapis magique desactive : tu es entre dans une zone protegee.");
                return;
            }
        }

        Location loc = player.getLocation();
        double velocityY = player.getVelocity().getY();
        boolean sneaking = player.isSneaking();

        // --- Sneak: on descend d'un bloc a chaque tick tant que le joueur maintient sneak ---
        if (sneaking) {
            session.setPlatformY(session.getPlatformY() - 1);
            session.setAscending(false);
        }
        // --- Saut detecte: le joueur monte d'un bloc, une seule fois par saut ---
        else if (velocityY > JUMP_VELOCITY_THRESHOLD && !session.isAscending()) {
            session.setPlatformY(session.getPlatformY() + 1);
            session.setAscending(true);
        }
        // Le joueur est retombe / stabilise: on autorise un nouveau saut
        else if (velocityY <= 0) {
            session.setAscending(false);
        }

        // Deplace la plateforme sous le joueur (suit sa position X/Z)
        placePlatform(session, loc.getBlockX(), loc.getBlockZ(), player.getWorld().getName());

        // Remet le joueur exactement au-dessus de la plateforme si besoin
        snapPlayerOnPlatform(player, session);
    }

    /**
     * Construit (ou deplace) la plateforme 3x3 de verre. Restaure d'abord les anciens blocs,
     * puis place les nouveaux et sauvegarde leur etat d'origine.
     */
    private void placePlatform(CarpetSession session, int centerX, int centerZ, String worldName) {
        restoreBlocks(session);

        Map<Location, BlockState> saved = new LinkedHashMap<Location, BlockState>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block block = plugin.getServer().getWorld(worldName)
                        .getBlockAt(centerX + dx, session.getPlatformY(), centerZ + dz);

                saved.put(block.getLocation(), block.getState());
                block.setType(Material.GLASS);
            }
        }

        session.setPlacedBlocks(saved);
    }

    /**
     * Restaure les blocs d'origine remplaces par la plateforme.
     */
    private void restoreBlocks(CarpetSession session) {
        Map<Location, BlockState> placed = session.getPlacedBlocks();
        if (placed == null || placed.isEmpty()) {
            return;
        }
        for (BlockState state : placed.values()) {
            state.update(true, false);
        }
        placed.clear();
    }

    /**
     * Teleporte le joueur pile au-dessus de la plateforme (garde X/Z/yaw/pitch, ajuste seulement Y).
     * Marque le teleport comme "interne" pour que l'ecouteur ne le confonde pas avec un vrai
     * /spawn, /tpa, /home, etc.
     */
    private void snapPlayerOnPlatform(Player player, CarpetSession session) {
        Location loc = player.getLocation();
        double expectedY = session.getPlatformY() + 1;

        if (Math.abs(loc.getY() - expectedY) > 0.05) {
            Location target = loc.clone();
            target.setY(expectedY);

            session.setInternalTeleport(true);
            player.teleport(target);
            session.setInternalTeleport(false);
        }
    }
}

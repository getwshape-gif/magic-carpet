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
 *
 * IMPORTANT : ce gestionnaire ne teleporte JAMAIS le joueur pour le "recaler" sur la
 * plateforme. On se contente de faire apparaitre les blocs exactement sous ses pieds
 * et de laisser la physique naturelle du jeu (gravite, collision) faire le reste.
 * Teleporter a chaque tick provoquait des saccades ("rollback") car ca entre en
 * conflit avec la prediction de mouvement du client Minecraft.
 */
public class CarpetManager {

    // Delai (en ticks) entre chaque mise a jour du tapis. 1 tick = a chaque frame serveur (20x/sec),
    // pour rester parfaitement fluide meme en sprint + saut avec Speed 2 ou plus.
    private static final long TICK_INTERVAL = 1L;

    // On ne verifie la presence d'un ennemi que toutes les X executions du tick ci-dessus
    // (10 executions * 1 tick = toutes les 0.5s, pour ne pas surcharger le serveur avec la reflexion).
    private static final int ENEMY_CHECK_EVERY_N_TICKS = 10;

    // Vitesse Y a partir de laquelle on considere que le joueur a saute.
    private static final double JUMP_VELOCITY_THRESHOLD = 0.2D;

    private final MagicCarpetPlugin plugin;
    private final Map<UUID, CarpetSession> sessions = new HashMap<UUID, CarpetSession>();

    // Joueurs qui ne doivent PAS prendre de degats de chute a leur prochain atterrissage.
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

        // Place la plateforme initiale pile sous les pieds actuels du joueur (aucun teleport requis).
        placePlatform(session, loc.getBlockX(), loc.getBlockZ(), player.getWorld().getName());

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
     * Coupe le tapis magique suite a la commande /mc.
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
        int currentFeetY = loc.getBlockY();

        if (sneaking) {
            // Descend progressivement tant que le joueur maintient sneak.
            session.setPlatformY(session.getPlatformY() - 1);
            session.setAscending(false);
        } else if (velocityY > JUMP_VELOCITY_THRESHOLD && !session.isAscending()) {
            // Saut detecte : on releve la plateforme d'un bloc pour que le joueur
            // "atterrisse" naturellement dessus au sommet de son saut, sans teleport.
            session.setPlatformY(session.getPlatformY() + 1);
            session.setAscending(true);
        } else if (velocityY <= 0) {
            session.setAscending(false);
            // Une fois stabilise (plus en train de sauter), on realigne la plateforme
            // sur la position reelle du joueur, pour rester colle a la physique naturelle
            // plutot que de forcer quoi que ce soit.
            session.setPlatformY(currentFeetY - 1);
        }

        // Deplace la plateforme sous le joueur (suit sa position X/Z et Y calculee ci-dessus).
        // Aucun teleport : seule la pose des blocs change, la physique du jeu fait le reste.
        placePlatform(session, loc.getBlockX(), loc.getBlockZ(), player.getWorld().getName());
    }

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
}

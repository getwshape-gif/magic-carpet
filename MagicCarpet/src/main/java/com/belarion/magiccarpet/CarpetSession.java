package com.belarion.magiccarpet;

import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;

/**
 * Represente le tapis magique actif d'un joueur.
 */
public class CarpetSession {

    private final UUID playerId;
    private int platformY;
    private boolean ascending;
    private Map<Location, BlockState> placedBlocks;
    private BukkitTask task;

    // True pendant qu'on teleporte nous-memes le joueur pour l'ajuster sur la plateforme
    // (pour ne pas que ce mini-teleport interne soit confondu avec un /spawn, /tpa, /home...).
    private boolean internalTeleport;

    public CarpetSession(UUID playerId, int platformY) {
        this.playerId = playerId;
        this.platformY = platformY;
        this.ascending = false;
        this.internalTeleport = false;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public int getPlatformY() {
        return platformY;
    }

    public void setPlatformY(int platformY) {
        this.platformY = platformY;
    }

    public boolean isAscending() {
        return ascending;
    }

    public void setAscending(boolean ascending) {
        this.ascending = ascending;
    }

    public Map<Location, BlockState> getPlacedBlocks() {
        return placedBlocks;
    }

    public void setPlacedBlocks(Map<Location, BlockState> placedBlocks) {
        this.placedBlocks = placedBlocks;
    }

    public BukkitTask getTask() {
        return task;
    }

    public void setTask(BukkitTask task) {
        this.task = task;
    }

    public boolean isInternalTeleport() {
        return internalTeleport;
    }

    public void setInternalTeleport(boolean internalTeleport) {
        this.internalTeleport = internalTeleport;
    }
}

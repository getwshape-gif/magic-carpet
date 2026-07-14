package com.belarion.magiccarpet;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * - Annule les degats de chute pour un joueur qui vient de desactiver son tapis magique en plein vol.
 * - Marque les deux joueurs impliques dans un coup PvP (direct ou via projectile) comme "en combat".
 * - Coupe le tapis magique des qu'une vraie teleportation externe est detectee (/spawn, /tpa, /home...).
 * - Nettoie les sessions a la deconnexion.
 */
public class CarpetListener implements Listener {

    private final CarpetManager carpetManager;
    private final CombatTracker combatTracker;

    public CarpetListener(CarpetManager carpetManager, CombatTracker combatTracker) {
        this.carpetManager = carpetManager;
        this.combatTracker = combatTracker;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();

        if (carpetManager.isFallImmune(player.getUniqueId())) {
            event.setCancelled(true);
            carpetManager.clearFallImmune(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPvpHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player victim = (Player) event.getEntity();
        Player attacker = null;

        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Projectile) {
            ProjectileSource source = ((Projectile) event.getDamager()).getShooter();
            if (source instanceof Player) {
                attacker = (Player) source;
            }
        }

        if (attacker == null) {
            return;
        }

        combatTracker.markInCombat(victim.getUniqueId());
        combatTracker.markInCombat(attacker.getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        // Notre propre ajustement de hauteur (le tapis qui suit le joueur) : on ignore.
        if (carpetManager.isInternalTeleport(player.getUniqueId())) {
            return;
        }

        // Vraie teleportation (/spawn, /tpa, /home, ender pearl, etc.) : on coupe le tapis.
        if (carpetManager.hasCarpet(player)) {
            carpetManager.removeDueToTeleport(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        carpetManager.handleQuit(event.getPlayer().getUniqueId());
        combatTracker.clear(event.getPlayer().getUniqueId());
    }
}

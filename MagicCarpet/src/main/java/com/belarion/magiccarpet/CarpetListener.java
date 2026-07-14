package com.belarion.magiccarpet;

import org.bukkit.Location;
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

    // Distance (en blocs) a partir de laquelle on considere qu'un teleport est une "vraie"
    // teleportation (/spawn, /tpa, /home, ender pearl lointain...) et pas juste notre propre
    // ajustement de hauteur du tapis (qui ne bouge le joueur que de 1-2 blocs maximum).
    private static final double REAL_TELEPORT_DISTANCE = 5.0D;

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

        // Tant que le tapis est actif, on annule TOUJOURS les degats de chute : la descente
        // en sneak retire puis replace un bloc sous le joueur a chaque tick, ce qui peut
        // generer de courtes micro-chutes que le jeu comptabilise, meme sans jamais couper
        // le tapis. En plus de ca, l'immunite ponctuelle couvre la chute juste apres une
        // desactivation (manuelle, ennemi, zone, teleportation).
        if (carpetManager.hasCarpet(player) || carpetManager.isFallImmune(player.getUniqueId())) {
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

        if (!carpetManager.hasCarpet(player)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        boolean worldChanged = to == null || from == null
                || to.getWorld() == null || from.getWorld() == null
                || !to.getWorld().equals(from.getWorld());

        double distance = worldChanged ? Double.MAX_VALUE : from.distance(to);

        // Petit ajustement (le tapis qui suit le joueur en hauteur) : on ignore.
        if (distance < REAL_TELEPORT_DISTANCE) {
            return;
        }

        // Vraie teleportation (/spawn, /tpa, /home, ender pearl lointain, etc.) : on coupe le tapis.
        carpetManager.removeDueToTeleport(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        carpetManager.handleQuit(event.getPlayer().getUniqueId());
        combatTracker.clear(event.getPlayer().getUniqueId());
    }
}

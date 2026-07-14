package com.belarion.magiccarpet;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Gere la commande /mc : active ou desactive le tapis magique du joueur.
 *
 * La desactivation est TOUJOURS autorisee (meme en combat / en zone protegee),
 * pour ne jamais bloquer un joueur en plein vol. Seule l'ACTIVATION est
 * soumise aux restrictions (zone, combat).
 */
public class CarpetCommand implements CommandExecutor {

    private final CarpetManager carpetManager;
    private final CombatTracker combatTracker;

    public CarpetCommand(CarpetManager carpetManager, CombatTracker combatTracker) {
        this.carpetManager = carpetManager;
        this.combatTracker = combatTracker;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Cette commande n'est utilisable que par un joueur.");
            return true;
        }

        Player player = (Player) sender;

        // Desactivation: toujours possible, aucune restriction.
        if (carpetManager.hasCarpet(player)) {
            carpetManager.removeCarpet(player);
            player.sendMessage(ChatColor.RED + "Pouf, ton tapis volant a disparu !");
            return true;
        }

        // --- Verifications avant activation ---

        if (FactionIntegration.isNearWorldSpawn(player.getLocation())
                || FactionIntegration.isInWarOrSafeZone(player.getLocation())) {
            player.sendMessage(ChatColor.RED + "Impossible d'utiliser le tapis magique ici (spawn / warzone).");
            return true;
        }

        if (combatTracker.isInCombat(player.getUniqueId())) {
            long seconds = combatTracker.getRemainingSeconds(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Impossible en combat ! Réessaie dans " + seconds + " secondes.");
            return true;
        }

        carpetManager.createCarpet(player);
        player.sendMessage(ChatColor.GREEN + "Pouf, ton tapis volant est arrivé !");
        return true;
    }
}

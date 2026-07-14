package com.belarion.magiccarpet;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin principal.
 * Enregistre la commande /mc, l'ecouteur de degats de chute/combat,
 * et lance le gestionnaire de sessions de tapis magique.
 */
public class MagicCarpetPlugin extends JavaPlugin {

    private CarpetManager carpetManager;
    private CombatTracker combatTracker;

    @Override
    public void onEnable() {
        this.combatTracker = new CombatTracker();
        this.carpetManager = new CarpetManager(this);

        // Commande /mc
        getCommand("mc").setExecutor(new CarpetCommand(carpetManager, combatTracker));

        // Ecouteur pour: annuler les degats de chute, detecter le combat,
        // et nettoyer a la deconnexion.
        getServer().getPluginManager().registerEvents(
                new CarpetListener(carpetManager, combatTracker), this);

        getLogger().info("MagicCarpet active - commande /mc prete.");
    }

    @Override
    public void onDisable() {
        // On retire tous les tapis actifs et on restaure les blocs d'origine
        if (carpetManager != null) {
            carpetManager.disableAll();
        }
        getLogger().info("MagicCarpet desactive.");
    }
}

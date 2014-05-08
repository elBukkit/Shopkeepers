package com.nisovin.shopkeepers;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class NPCListener implements Listener {

	final ShopkeepersPlugin plugin;

	public NPCListener(ShopkeepersPlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	void onEntityInteract(PlayerInteractEntityEvent event) {
        Entity rightClicked = event.getRightClicked();
	if (rightClicked != null && rightClicked instanceof LivingEntity && rightClicked.hasMetadata("NPC")) {
            LivingEntity entity = (LivingEntity)event.getRightClicked();
			ShopkeepersPlugin.debug("Player " + event.getPlayer().getName() + " is interacting with NPC at " + entity.getLocation());
			Shopkeeper shopkeeper = plugin.activeShopkeepers.get("NPC" + entity.getEntityId());

			if (event.isCancelled()) {
				ShopkeepersPlugin.debug("  NPC interaction, but already cancelled");
			} else if (shopkeeper != null) {
				plugin.handleShopkeeperInteraction(event.getPlayer(), shopkeeper);
				event.setCancelled(true);
			}
		}
	}
}
package com.nisovin.shopkeepers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.nisovin.shopkeepers.events.*;
import com.nisovin.shopkeepers.pluginhandlers.*;
import com.nisovin.shopkeepers.shopobjects.*;
import com.nisovin.shopkeepers.shoptypes.*;

import com.nisovin.shopkeepers.compat.NMSManager;

public class ShopkeepersPlugin extends JavaPlugin {

	static ShopkeepersPlugin plugin;

	private boolean debug = false;

	Map<String, List<Shopkeeper>> allShopkeepersByChunk = new HashMap<String, List<Shopkeeper>>();
	Map<String, Shopkeeper> activeShopkeepers = new HashMap<String, Shopkeeper>();
	Map<String, String> editing = new HashMap<String, String>();
	Map<String, String> naming = Collections.synchronizedMap(new HashMap<String, String>());
	Map<String, String> purchasing = new HashMap<String, String>();
	Map<String, String> hiring = new HashMap<String, String>();
	Map<String, List<String>> recentlyPlacedChests = new HashMap<String, List<String>>();
	Map<String, ShopkeeperType> selectedShopType = new HashMap<String, ShopkeeperType>();
	Map<String, ShopObjectType> selectedShopObjectType = new HashMap<String, ShopObjectType>();
	Map<String, Block> selectedChest = new HashMap<String, Block>();

	private boolean dirty = false;
	private int chunkLoadSaveTask = -1;

	BlockFace[] chestProtectFaces = { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST };
	BlockFace[] hopperProtectFaces = { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN };

	private CreatureForceSpawnListener creatureForceSpawnListener = null;

	@Override
	public void onEnable() {
		plugin = this;

		// try to load suitable NMS code
		NMSManager.load(this);
		if (NMSManager.getProvider() == null) {
			plugin.getLogger().severe("Incompatible server version: Shopkeepers cannot be enabled.");
			this.setEnabled(false);
			return;
		}

		// get config
		File file = new File(getDataFolder(), "config.yml");
		if (!file.exists()) {
			saveDefaultConfig();
		}
		reloadConfig();
		Configuration config = getConfig();
		if (Settings.loadConfiguration(config)) {
			// if values were missing -> add those to the file and save it
			saveConfig();
		}
		debug = config.getBoolean("debug", debug);

		// get lang config
		String lang = config.getString("language", "en");
		File langFile = new File(getDataFolder(), "language-" + lang + ".yml");
		if (!langFile.exists() && this.getResource("language-" + lang + ".yml") != null) {
			saveResource("language-" + lang + ".yml", false);
		}
		if (langFile.exists()) {
			try {
				YamlConfiguration langConfig = new YamlConfiguration();
				langConfig.load(langFile);
				Settings.loadLanguageConfiguration(langConfig);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		// register force-creature-spawn event:
		PluginManager pm = getServer().getPluginManager();
		if (Settings.bypassSpawnBlocking) {
			creatureForceSpawnListener = new CreatureForceSpawnListener();
			pm.registerEvents(creatureForceSpawnListener, this);
		}

		// load shopkeeper saved data
		load();

		// spawn villagers in loaded chunks
		for (World world : Bukkit.getWorlds()) {
			for (Chunk chunk : world.getLoadedChunks()) {
				loadShopkeepersInChunk(chunk);
			}
		}

		// process additional perms
		String[] perms = Settings.maxShopsPermOptions.replace(" ", "").split(",");
		for (String perm : perms) {
			if (Bukkit.getPluginManager().getPermission("shopkeeper.maxshops." + perm) == null) {
				Bukkit.getPluginManager().addPermission(new Permission("shopkeeper.maxshops." + perm, PermissionDefault.FALSE));
			}
		}

		// register events

		pm.registerEvents(new PlayerJoinListener(), this);
		pm.registerEvents(new ShopListener(this), this);
		pm.registerEvents(new CreateListener(this), this);
		if (Settings.enableVillagerShops) {
			pm.registerEvents(new VillagerListener(this), this);
		}
		if (Settings.enableSignShops) {
			pm.registerEvents(new BlockListener(this), this);
		}
		if (Settings.enableWitchShops) {
			pm.registerEvents(new WitchListener(this), this);
		}
		if (Settings.enableCreeperShops) {
			pm.registerEvents(new CreeperListener(this), this);
		}
        if (Settings.enableCitizenShops) {
            try {
                Plugin plugin = pm.getPlugin("Citizens");
                if (plugin == null) {
                    warning("Citizens Shops enabled, but Citizens plugin not found.");
                    Settings.enableCitizenShops = false;
                } else {
                    getLogger().info("Citizens found, enabling NPC shopkeepers");
                    NPCShopkeeperTrait.registerTrait();
                    pm.registerEvents(new NPCListener(this), this);
                }
            } catch (Throwable ex) {

            }

        }
		if (Settings.blockVillagerSpawns) {
			pm.registerEvents(new BlockSpawnListener(), this);
		}
		if (Settings.protectChests) {
			pm.registerEvents(new ChestProtectListener(this), this);
		}
		if (Settings.deleteShopkeeperOnBreakChest) {
			pm.registerEvents(new ChestBreakListener(this), this);
		}

		// let's update the shopkeepers for all online players:
		for (Player player : Bukkit.getOnlinePlayers()) {
			this.updateShopkeepersForPlayer(player);
		}

		// start teleporter
		Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
			public void run() {
				List<Shopkeeper> readd = new ArrayList<Shopkeeper>();
				Iterator<Map.Entry<String, Shopkeeper>> iter = activeShopkeepers.entrySet().iterator();
				while (iter.hasNext()) {
					Shopkeeper shopkeeper = iter.next().getValue();
					boolean update = shopkeeper.teleport();
					if (update) {
						readd.add(shopkeeper);
						iter.remove();
					}
				}
				for (Shopkeeper shopkeeper : readd) {
					if (shopkeeper.isActive()) {
						activeShopkeepers.put(shopkeeper.getId(), shopkeeper);
					}
				}
			}
		}, 200, 200);

		// start verifier
		if (Settings.enableSpawnVerifier) {
			Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
				public void run() {
					int count = 0;
					for (String chunkStr : allShopkeepersByChunk.keySet()) {
						if (isChunkLoaded(chunkStr)) {
							List<Shopkeeper> shopkeepers = allShopkeepersByChunk.get(chunkStr);
							for (Shopkeeper shopkeeper : shopkeepers) {
								if (!shopkeeper.isActive()) {
									boolean spawned = shopkeeper.spawn();
									if (spawned) {
										activeShopkeepers.put(shopkeeper.getId(), shopkeeper);
										count++;
									} else {
										debug("Failed to spawn shopkeeper at " + shopkeeper.getPositionString());
									}
								}
							}
						}
					}
					if (count > 0) {
						debug("Spawn verifier: " + count + " shopkeepers respawned");
					}
				}
			}, 600, 1200);
		}

		// start saver
		if (!Settings.saveInstantly) {
			Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
				public void run() {
					if (dirty) {
						saveReal();
						dirty = false;
					}
				}
			}, 6000, 6000);
		}

	}

	@Override
	public void onDisable() {
		if (dirty) {
			saveReal();
			dirty = false;
		}

		for (String playerName : editing.keySet()) {
			Player player = Bukkit.getPlayerExact(playerName);
			if (player != null) {
				player.closeInventory();
			}
		}
		editing.clear();

		for (String playerName : purchasing.keySet()) {
			Player player = Bukkit.getPlayerExact(playerName);
			if (player != null) {
				player.closeInventory();
			}
		}
		purchasing.clear();

		for (Shopkeeper shopkeeper : activeShopkeepers.values()) {
			shopkeeper.remove();
		}
		activeShopkeepers.clear();
		allShopkeepersByChunk.clear();

		selectedShopType.clear();
		selectedShopObjectType.clear();
		selectedChest.clear();

		HandlerList.unregisterAll((Plugin) this);
		Bukkit.getScheduler().cancelTasks(this);

		plugin = null;
	}

	/**
	 * Reloads the plugin.
	 */
	public void reload() {
		onDisable();
		onEnable();
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length > 0 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("shopkeeper.reload")) {
			// reload command
			reload();
			sender.sendMessage(ChatColor.GREEN + "Shopkeepers plugin reloaded!");
			return true;
		} else if (args.length == 1 && args[0].equalsIgnoreCase("debug") && sender.isOp()) {
			// toggle debug command
			debug = !debug;
			sender.sendMessage(ChatColor.GREEN + "Debug mode " + (debug ? "enabled" : "disabled"));
			return true;

		} else if (args.length == 1 && args[0].equals("check") && sender.isOp()) {
			for (Shopkeeper shopkeeper : activeShopkeepers.values()) {
				if (shopkeeper.isActive()) {
					Location loc = shopkeeper.getActualLocation();
					sender.sendMessage("Shopkeeper at " + shopkeeper.getPositionString() + ": active (" + (loc != null ? loc.toString() : "maybe not?!?") + ")");
				} else {
					sender.sendMessage("Shopkeeper at " + shopkeeper.getPositionString() + ": INACTIVE!");
				}
			}
			return true;

		} else if (sender instanceof Player) {
			Player player = (Player) sender;
			@SuppressWarnings("deprecation")
			Block block = player.getTargetBlock(null, 10); // TODO: fix this when API becomes available

			// transfer ownership
			if (args.length == 2 && args[0].equalsIgnoreCase("transfer") && player.hasPermission("shopkeeper.transfer")) {
				Player newOwner = Bukkit.getPlayer(args[1]);
				if (newOwner == null) {
					this.sendMessage(player, Settings.msgUnknownPlayer);
					return true;
				}
				if (block.getType() != Material.CHEST) {
					this.sendMessage(player, Settings.msgMustTargetChest);
					return true;
				}
				List<PlayerShopkeeper> shopkeepers = getShopkeeperOwnersOfChest(block);
				if (shopkeepers.size() == 0) {
					this.sendMessage(player, Settings.msgUnusedChest);
					return true;
				}
				if (!player.isOp() && !player.hasPermission("shopkeeper.bypass")) {
					for (PlayerShopkeeper shopkeeper : shopkeepers) {
						if (!shopkeeper.isOwner(player)) {
							this.sendMessage(player, Settings.msgNotOwner);
							return true;
						}
					}
				}
				for (PlayerShopkeeper shopkeeper : shopkeepers) {
					shopkeeper.setOwner(newOwner);
				}
				save();
				this.sendMessage(player, Settings.msgOwnerSet.replace("{owner}", newOwner.getName()));
				return true;
			}

			// set for hire
			if (args.length == 1 && args[0].equalsIgnoreCase("setforhire") && player.hasPermission("shopkeeper.setforhire")) {
				if (block.getType() != Material.CHEST) {
					this.sendMessage(player, Settings.msgMustTargetChest);
					return true;
				}
				List<PlayerShopkeeper> shopkeepers = getShopkeeperOwnersOfChest(block);
				if (shopkeepers.size() == 0) {
					this.sendMessage(player, Settings.msgUnusedChest);
					return true;
				}
				if (!player.isOp() && !player.hasPermission("shopkeeper.bypass")) {
					for (PlayerShopkeeper shopkeeper : shopkeepers) {
						if (!shopkeeper.isOwner(player)) {
							this.sendMessage(player, Settings.msgNotOwner);
							return true;
						}
					}
				}
				ItemStack hireCost = player.getItemInHand();
				if (hireCost == null || hireCost.getType() == Material.AIR || hireCost.getAmount() == 0) {
					this.sendMessage(player, Settings.msgMustHoldHireItem);
					return true;
				}
				for (PlayerShopkeeper shopkeeper : shopkeepers) {
					shopkeeper.setForHire(true, hireCost.clone());
				}
				save();
				this.sendMessage(player, Settings.msgSetForHire);
				return true;
			}

			// open remote shop
			if (args.length >= 2 && args[0].equalsIgnoreCase("remote") && player.hasPermission("shopkeeper.remote")) {
				String shopName = args[1];
				for (int i = 2; i < args.length; i++) {
					shopName += " " + args[i];
				}
				boolean opened = false;
				for (List<Shopkeeper> list : allShopkeepersByChunk.values()) {
					for (Shopkeeper shopkeeper : list) {
						if (shopkeeper instanceof AdminShopkeeper && shopkeeper.getName() != null && ChatColor.stripColor(shopkeeper.getName()).equalsIgnoreCase(shopName)) {
							openTradeWindow(shopkeeper, player);
							opened = true;
							break;
						}
					}
					if (opened) break;
				}
				if (!opened) {
					this.sendMessage(player, Settings.msgUnknownShopkeeper);
				}
				return true;
			}

			// get the spawn location for the shopkeeper
			if (block != null && block.getType() != Material.AIR) {
				if (Settings.createPlayerShopWithCommand && block.getType() == Material.CHEST) {
					// check if already a chest
					if (isChestProtected(null, block)) {
						return true;
					}
					// check for recently placed
					if (Settings.requireChestRecentlyPlaced) {
						List<String> list = plugin.recentlyPlacedChests.get(player.getName());
						if (list == null || !list.contains(block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ())) {
							sendMessage(player, Settings.msgChestNotPlaced);
							return true;
						}
					}
					// check for permission
					if (Settings.simulateRightClickOnCommand) {
						PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, new ItemStack(Material.AIR), block, BlockFace.UP);
						Bukkit.getPluginManager().callEvent(event);
						if (event.isCancelled()) {
							return true;
						}
					}
					// create the player shopkeeper
					ShopkeeperType shopType = ShopkeeperType.next(player, null);
					ShopObjectType shopObjType = ShopObjectType.next(player, null);
					if (args != null && args.length > 0) {
						if (args.length >= 1) {
							if ((args[0].toLowerCase().startsWith("norm") || args[0].toLowerCase().startsWith("sell"))) {
								shopType = ShopkeeperType.PLAYER_NORMAL;
							} else if (args[0].toLowerCase().startsWith("book")) {
								shopType = ShopkeeperType.PLAYER_BOOK;
							} else if (args[0].toLowerCase().startsWith("buy")) {
								shopType = ShopkeeperType.PLAYER_BUY;
							} else if (args[0].toLowerCase().startsWith("trad")) {
								shopType = ShopkeeperType.PLAYER_TRADE;
							} else if (args[0].toLowerCase().equals("villager") && Settings.enableVillagerShops) {
								shopObjType = ShopObjectType.VILLAGER;
							} else if (args[0].toLowerCase().equals("sign") && Settings.enableSignShops) {
								shopObjType = ShopObjectType.SIGN;
							} else if (args[0].toLowerCase().equals("witch") && Settings.enableWitchShops) {
								shopObjType = ShopObjectType.WITCH;
							} else if (args[0].toLowerCase().equals("creeper") && Settings.enableCreeperShops) {
								shopObjType = ShopObjectType.CREEPER;
							} else if (args[0].toLowerCase().equals("npc") && Settings.enableCitizenShops) {
                                shopObjType = ShopObjectType.NPC;
                            }
						}
						if (args.length >= 2) {
							if (args[1].equalsIgnoreCase("villager") && Settings.enableVillagerShops) {
								shopObjType = ShopObjectType.VILLAGER;
							} else if (args[1].equalsIgnoreCase("sign") && Settings.enableSignShops) {
								shopObjType = ShopObjectType.SIGN;
							} else if (args[1].equalsIgnoreCase("witch") && Settings.enableWitchShops) {
								shopObjType = ShopObjectType.WITCH;
							} else if (args[1].equalsIgnoreCase("creeper") && Settings.enableCreeperShops) {
								shopObjType = ShopObjectType.CREEPER;
							} else if (args[1].equalsIgnoreCase("npc") && Settings.enableCitizenShops) {
                                shopObjType = ShopObjectType.NPC;
                            }
						}
						if (shopType != null && !shopType.hasPermission(player)) {
							shopType = null;
						}
						if (shopObjType != null && !shopObjType.hasPermission(player)) {
							shopObjType = null;
						}
					}
					if (shopType != null) {
						Shopkeeper shopkeeper = createNewPlayerShopkeeper(player, block, block.getLocation().add(0, 1.5, 0), shopType, shopObjType);
						if (shopkeeper != null) {
							sendCreatedMessage(player, shopType);
						}
					}
				} else if (player.hasPermission("shopkeeper.admin")) {
					// create the admin shopkeeper
					ShopObjectType shopObjType = ShopObjectType.VILLAGER;
					Location loc = block.getLocation().add(0, 1.5, 0);
					if (args.length > 0) {
						if (args[0].equals("sign") && Settings.enableSignShops) {
							shopObjType = ShopObjectType.SIGN;
							loc = block.getLocation();
						} else if (args[0].equals("witch") && Settings.enableWitchShops) {
							shopObjType = ShopObjectType.WITCH;
						} else if (args[0].equals("creeper") && Settings.enableCreeperShops) {
							shopObjType = ShopObjectType.CREEPER;
						} else if (args[0].equals("npc") && Settings.enableCitizenShops) {
                            shopObjType = ShopObjectType.NPC;
                        }
					}

					Shopkeeper shopkeeper = createNewAdminShopkeeper(loc, shopObjType);
					if (shopkeeper != null) {
						sendMessage(player, Settings.msgAdminShopCreated);

						// run event
						Bukkit.getPluginManager().callEvent(new ShopkeeperCreatedEvent(player, shopkeeper));
					}
				}
			} else {
				sendMessage(player, Settings.msgShopCreateFail);
			}

			return true;
		} else {
			sender.sendMessage("You must be a player to create a shopkeeper.");
			sender.sendMessage("Use 'shopkeeper reload' to reload the plugin.");
			return true;
		}
	}

	/**
	 * Creates a new admin shopkeeper and spawns it into the world.
	 * 
	 * @param location
	 *            the block location the shopkeeper should spawn
	 * @param shopObjectType
	 *            the shopkeeper's profession, a number from 0 to 5
	 * @return the shopkeeper created
	 */
	public Shopkeeper createNewAdminShopkeeper(Location location, ShopObjectType shopObjectType) {
		// create the shopkeeper (and spawn it)
		Shopkeeper shopkeeper = ShopkeeperType.ADMIN.createShopkeeper(null, null, location, shopObjectType);
		shopkeeper.spawn();
		activeShopkeepers.put(shopkeeper.getId(), shopkeeper);
		addShopkeeper(shopkeeper);

		return shopkeeper;
	}

    /**
     * Creates a new admin shopkeeper and spawns it into the world.
     *
     * @param location
     *            the block location the shopkeeper should spawn
     * @param shopObjectType
     *           The type of shop
     * @param entity
     *           The entity to use for backing the shop, only applies to some shop types
     * @return the shopkeeper created
     */
    public Shopkeeper createNewAdminShopkeeper(Location location, ShopObjectType shopObjectType, LivingEntity entity) {
        // create the shopkeeper (and spawn it)
        Shopkeeper shopkeeper = ShopkeeperType.ADMIN.createShopkeeper(null, null, location, shopObjectType);
        shopkeeper.attach(entity);
        activeShopkeepers.put(shopkeeper.getId(), shopkeeper);
        addShopkeeper(shopkeeper);

        return shopkeeper;
    }

	/**
	 * Creates a new player-based shopkeeper and spawns it into the world.
	 * 
	 * @param player
	 *            the player who created the shopkeeper
	 * @param chest
	 *            the backing chest for the shop
	 * @param location
	 *            the block location the shopkeeper should spawn
	 * @param shopType
	 *            the shopkeeper's profession, a number from 0 to 5
	 * @param shopObjectType
	 *            the player shop type (0=normal, 1=book, 2=buy)
	 * @return the shopkeeper created
	 */
	public Shopkeeper createNewPlayerShopkeeper(Player player, Block chest, Location location, ShopkeeperType shopType, ShopObjectType shopObjectType) {
		if (shopType == null || shopObjectType == null) {
			return null;
		}

		// check worldguard
		if (Settings.enableWorldGuardRestrictions) {
			if (!WorldGuardHandler.canBuild(player, location)) {
				plugin.sendMessage(player, Settings.msgShopCreateFail);
				return null;
			}
		}

		// check towny
		if (Settings.enableTownyRestrictions) {
			if (!TownyHandler.isCommercialArea(location)) {
				plugin.sendMessage(player, Settings.msgShopCreateFail);
				return null;
			}
		}

		int maxShops = Settings.maxShopsPerPlayer;
		String[] maxShopsPermOptions = Settings.maxShopsPermOptions.replace(" ", "").split(",");
		for (String perm : maxShopsPermOptions) {
			if (player.hasPermission("shopkeeper.maxshops." + perm)) {
				maxShops = Integer.parseInt(perm);
			}
		}

		// call event
		CreatePlayerShopkeeperEvent event = new CreatePlayerShopkeeperEvent(player, chest, location, shopType, maxShops);
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()) {
			return null;
		} else {
			location = event.getSpawnLocation();
			shopType = event.getType();
			maxShops = event.getMaxShopsForPlayer();
		}

		// count owned shops
		if (maxShops > 0) {
			int count = 0;
			for (List<Shopkeeper> list : allShopkeepersByChunk.values()) {
				for (Shopkeeper shopkeeper : list) {
					if (shopkeeper instanceof PlayerShopkeeper && ((PlayerShopkeeper) shopkeeper).isOwner(player)) {
						count++;
					}
				}
			}
			if (count >= maxShops) {
				sendMessage(player, Settings.msgTooManyShops);
				return null;
			}
		}

		// create the shopkeeper
		Shopkeeper shopkeeper = shopType.createShopkeeper(player, chest, location, shopObjectType);

		// spawn and save the shopkeeper
		if (shopkeeper != null) {
			shopkeeper.spawn();
			activeShopkeepers.put(shopkeeper.getId(), shopkeeper);
			addShopkeeper(shopkeeper);
			// run event
			Bukkit.getPluginManager().callEvent(new ShopkeeperCreatedEvent(player, shopkeeper));
		}

		return shopkeeper;
	}

	/**
	 * Gets the shopkeeper by the villager's entity id.
	 * 
	 * @param entityId
	 *            the entity id of the villager
	 * @return the Shopkeeper, or null if the entity with the given id is not a shopkeeper
	 */
	public Shopkeeper getShopkeeperByEntityId(int entityId) {
		return activeShopkeepers.get("entity" + entityId);
	}

	/**
	 * Gets all shopkeepers from a given chunk. Returns null if there are no shopkeepers in that chunk.
	 * 
	 * @param world
	 *            the world
	 * @param x
	 *            chunk x-coordinate
	 * @param z
	 *            chunk z-coordinate
	 * @return a list of shopkeepers, or null if there are none
	 */
	public List<Shopkeeper> getShopkeepersInChunk(String world, int x, int z) {
		return allShopkeepersByChunk.get(world + "," + x + "," + z);
	}

	/**
	 * Checks if a given entity is a Shopkeeper.
	 * 
	 * @param entity
	 *            the entity to check
	 * @return whether the entity is a Shopkeeper
	 */
	public boolean isShopkeeper(Entity entity) {
		return activeShopkeepers.containsKey("entity" + entity.getEntityId());
	}

	public boolean isShopkeeperEditorWindow(Inventory inventory) {
		return inventory.getTitle().equals(Settings.editorTitle);
	}

	public boolean isShopkeeperHireWindow(Inventory inventory) {
		return inventory.getTitle().equals(Settings.forHireTitle);
	}

	void addShopkeeper(Shopkeeper shopkeeper) {
		// add to chunk list
		List<Shopkeeper> list = allShopkeepersByChunk.get(shopkeeper.getChunk());
		if (list == null) {
			list = new ArrayList<Shopkeeper>();
			allShopkeepersByChunk.put(shopkeeper.getChunk(), list);
		}
		list.add(shopkeeper);
		// save all data
		save();
	}

	boolean sendCreatedMessage(Player player, ShopkeeperType shopType) {
		if (shopType == ShopkeeperType.PLAYER_NORMAL) {
			plugin.sendMessage(player, Settings.msgPlayerShopCreated);
			return true;
		} else if (shopType == ShopkeeperType.PLAYER_BOOK) {
			plugin.sendMessage(player, Settings.msgBookShopCreated);
			return true;
		} else if (shopType == ShopkeeperType.PLAYER_BUY) {
			plugin.sendMessage(player, Settings.msgBuyShopCreated);
			return true;
		} else if (shopType == ShopkeeperType.PLAYER_TRADE) {
			plugin.sendMessage(player, Settings.msgTradeShopCreated);
			return true;
		}
		return false;
	}

	void handleShopkeeperInteraction(Player player, Shopkeeper shopkeeper) {
		if (shopkeeper == null) {
			ShopkeepersPlugin.debug("  The given shopkeper is null! Not opening any editor/trade window...");
			return;
		}

		if (player.isSneaking()) {
			// modifying a shopkeeper
			ShopkeepersPlugin.debug("  Opening editor window...");
			boolean isEditing = shopkeeper.onEdit(player);
			if (isEditing) {
				ShopkeepersPlugin.debug("  Editor window opened");
				editing.put(player.getName(), shopkeeper.getId());
			} else {
				ShopkeepersPlugin.debug("  Editor window NOT opened");
			}
		} else {
			if (shopkeeper instanceof PlayerShopkeeper && ((PlayerShopkeeper) shopkeeper).isForHire() && player.hasPermission("shopkeeper.hire")) {
				// show hire interface
				openHireWindow((PlayerShopkeeper) shopkeeper, player);
				hiring.put(player.getName(), shopkeeper.getId());
			} else {
				// trading with shopkeeper
				ShopkeepersPlugin.debug("  Opening trade window...");

				// check for special conditions, which else would remove the player's spawn egg when attempting to open the trade window via nms/reflection,
				// because of minecraft's spawnChildren code
				if (player.getItemInHand().getType() == Material.MONSTER_EGG) {
					ShopkeepersPlugin.debug("  Player is holding a spawn egg");
					this.sendMessage(player, Settings.msgCantOpenShopWithSpawnEgg);
					return;
				}
				OpenTradeEvent event = new OpenTradeEvent(player, shopkeeper);
				Bukkit.getPluginManager().callEvent(event);
				if (event.isCancelled()) {
					ShopkeepersPlugin.debug("  Trade window opening cancelled by another plugin");
					return;
				}
				// open trade window
				openTradeWindow(shopkeeper, player);
				purchasing.put(player.getName(), shopkeeper.getId());
				ShopkeepersPlugin.debug("  Trade window opened");
			}
		}
	}

	// returns false, if the player wasn't able to hire this villager
	@SuppressWarnings("deprecation")
	// because of player.updateInventory()
	boolean handleHireOtherVillager(Player player, Villager villager) {
		// hire him if holding his hiring item
		ItemStack inHand = player.getItemInHand();
		if (Settings.isHireItem(inHand)) {
			Inventory inventory = player.getInventory();
			// check if the player has enough of those hiring items
			int costs = Settings.hireOtherVillagersCosts;
			if (costs > 0) {
				if (ItemUtils.hasInventoryItemsAtLeast(inventory, Settings.hireItem, (short) Settings.hireItemData, costs)) {
					debug("  Villager hiring: the player has the needed amount of hiring items");
					int inHandAmount = inHand.getAmount();
					int remaining = inHandAmount - costs;
					debug("  Villager hiring: in hand=" + inHandAmount + " costs=" + costs + " remaining=" + remaining);
					if (remaining > 0) {
						inHand.setAmount(remaining);
					} else { // remaining <= 0
						player.setItemInHand(null); // remove item in hand
						if (remaining < 0) {
							// remove remaining costs from inventory
							ItemUtils.removeItemsFromInventory(inventory, Settings.hireItem, (short) Settings.hireItemData, -remaining);
						}
					}
				} else {
					sendMessage(player, Settings.msgCantHire);
					return false;
				}
			}

			// give player the creation item
			ItemStack creationItem = Settings.createCreationItem();
			HashMap<Integer, ItemStack> remaining = inventory.addItem(creationItem);
			if (!remaining.isEmpty()) {
				villager.getWorld().dropItem(villager.getLocation(), creationItem);
			}

			// remove the npc
			villager.remove();

			// update client's inventory
			player.updateInventory();

			sendMessage(player, Settings.msgHired);
			return true;
		} else {
			sendMessage(player, Settings.msgVillagerForHire.replace("{costs}", String.valueOf(Settings.hireOtherVillagersCosts)).replace("{hire-item}", Settings.hireItem.toString()));
		}
		return false;
	}

	void closeTradingForShopkeeper(final String id) {
		Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
			public void run() {
				Iterator<String> editors = editing.keySet().iterator();
				while (editors.hasNext()) {
					String name = editors.next();
					if (editing.get(name).equals(id)) {
						editors.remove();
						Player player = Bukkit.getPlayerExact(name);
						if (player != null) {
							player.closeInventory();
						}
					}
				}
				Iterator<String> purchasers = purchasing.keySet().iterator();
				while (purchasers.hasNext()) {
					String name = purchasers.next();
					if (purchasing.get(name).equals(id)) {
						purchasers.remove();
						Player player = Bukkit.getPlayerExact(name);
						if (player != null) {
							player.closeInventory();
						}
					}
				}
				Iterator<String> hirers = hiring.keySet().iterator();
				while (hirers.hasNext()) {
					String name = hirers.next();
					if (hiring.get(name).equals(id)) {
						hirers.remove();
						Player player = Bukkit.getPlayerExact(name);
						if (player != null) {
							player.closeInventory();
						}
					}
				}
			}
		}, 1);
	}

	void closeInventory(final HumanEntity player) {
		Bukkit.getScheduler().scheduleSyncDelayedTask(ShopkeepersPlugin.plugin, new Runnable() {
			public void run() {
				player.closeInventory();
			}
		}, 1);
	}

	boolean openTradeWindow(Shopkeeper shopkeeper, Player player) {
		return NMSManager.getProvider().openTradeWindow(shopkeeper, player);
	}

	void openHireWindow(PlayerShopkeeper shopkeeper, Player player) {
		Inventory inv = Bukkit.createInventory(player, 9, ChatColor.translateAlternateColorCodes('&', Settings.forHireTitle));

		ItemStack hireItem = Settings.createHireButtonItem();
		inv.setItem(2, hireItem);
		inv.setItem(6, hireItem);

		ItemStack hireCost = shopkeeper.getHireCost();
		if (hireCost == null) return;
		inv.setItem(4, hireCost);

		player.openInventory(inv);
	}

	boolean isChestProtected(Player player, Block block) {
		for (Shopkeeper shopkeeper : activeShopkeepers.values()) {
			if (shopkeeper instanceof PlayerShopkeeper) {
				PlayerShopkeeper pshop = (PlayerShopkeeper) shopkeeper;
				if ((player == null || !pshop.isOwner(player)) && pshop.usesChest(block)) {
					return true;
				}
			}
		}
		return false;
	}

	List<PlayerShopkeeper> getShopkeeperOwnersOfChest(Block block) {
		List<PlayerShopkeeper> owners = new ArrayList<PlayerShopkeeper>();
		for (Shopkeeper shopkeeper : activeShopkeepers.values()) {
			if (shopkeeper instanceof PlayerShopkeeper) {
				PlayerShopkeeper pshop = (PlayerShopkeeper) shopkeeper;
				if (pshop.usesChest(block)) {
					owners.add(pshop);
				}
			}
		}
		return owners;
	}

	void sendMessage(Player player, String message) {
		// skip print "empty" messages:
		if (message == null || message.isEmpty()) return;
		message = ChatColor.translateAlternateColorCodes('&', message);
		String[] msgs = message.split("\n");
		for (String msg : msgs) {
			player.sendMessage(msg);
		}
	}

	void loadShopkeepersInChunk(Chunk chunk) {
		List<Shopkeeper> shopkeepers = allShopkeepersByChunk.get(chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ());
		if (shopkeepers != null) {
			debug("Loading " + shopkeepers.size() + " shopkeepers in chunk " + chunk.getX() + "," + chunk.getZ());
			for (Shopkeeper shopkeeper : shopkeepers) {
				if (!shopkeeper.isActive() && shopkeeper.needsSpawned()) {
					Shopkeeper oldShopkeeper = activeShopkeepers.get(shopkeeper.getId());
					if (this.debug && oldShopkeeper != null && oldShopkeeper.getShopObject() instanceof LivingEntityShop) {
						LivingEntityShop oldLivingShop = (LivingEntityShop) oldShopkeeper.getShopObject();
						LivingEntity oldEntity = oldLivingShop.getEntity();
						debug("Old, active shopkeeper was found (unloading probably has been skipped earlier): " + (oldEntity == null ? "null" : (oldEntity.getUniqueId() + " | " + (oldEntity.isDead() ? "dead | " : "alive | ") + (oldEntity
								.isValid() ? "valid" : "invalid"))));
					}
					boolean spawned = shopkeeper.spawn();
					if (spawned) {
						activeShopkeepers.put(shopkeeper.getId(), shopkeeper);
					} else {
						getLogger().warning("Failed to spawn shopkeeper at " + shopkeeper.getPositionString());
					}
				}
			}
			// save
			dirty = true;
			if (Settings.saveInstantly) {
				if (chunkLoadSaveTask < 0) {
					chunkLoadSaveTask = Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
						public void run() {
							if (dirty) {
								saveReal();
								dirty = false;
							}
							chunkLoadSaveTask = -1;
						}
					}, 600);
				}
			}
		}
	}

	private boolean isChunkLoaded(String chunkStr) {
		String[] chunkData = chunkStr.split(",");
		World w = getServer().getWorld(chunkData[0]);
		if (w != null) {
			int x = Integer.parseInt(chunkData[1]);
			int z = Integer.parseInt(chunkData[2]);
			return w.isChunkLoaded(x, z);
		}
		return false;
	}

	private void load() {
		File file = new File(getDataFolder(), "save.yml");
		if (!file.exists()) return;

		YamlConfiguration config = new YamlConfiguration();
		Scanner scanner = null;
		FileInputStream stream = null;
		try {
			if (Settings.fileEncoding != null && !Settings.fileEncoding.isEmpty()) {
				stream = new FileInputStream(file);
				scanner = new Scanner(stream, Settings.fileEncoding);
				scanner.useDelimiter("\\A");
				if (!scanner.hasNext()) return; // file is completely empty -> no shopkeeper data is available
				String data = scanner.next();
				config.loadFromString(data);
			} else {
				config.load(file);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return;
		} finally {
			if (scanner != null) scanner.close();
			if (stream != null) {
				try {
					stream.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		Set<String> keys = config.getKeys(false);
		for (String key : keys) {
			ConfigurationSection section = config.getConfigurationSection(key);
			ShopkeeperType shopType = ShopkeeperType.getTypeFromName(section.getString("type"));
			// unknown shop type but owner entry? -> default to normal player shop type
			if (shopType == ShopkeeperType.ADMIN && section.contains("owner")) shopType = ShopkeeperType.PLAYER_NORMAL;
			Shopkeeper shopkeeper = shopType.createShopkeeper(section);
			if (shopkeeper == null) return;

			// check if shop is too old
			if (Settings.playerShopkeeperInactiveDays > 0 && shopkeeper instanceof PlayerShopkeeper) {
				PlayerShopkeeper playerShop = (PlayerShopkeeper) shopkeeper;
				UUID ownerUUID = playerShop.getOwnerUUID();
				// TODO: this potentially could freeze, but shouldn't be a big issue here as we are inside the load method which only gets called once per plugin load
				OfflinePlayer offlinePlayer = ownerUUID != null ? Bukkit.getOfflinePlayer(ownerUUID) : Bukkit.getOfflinePlayer(playerShop.getOwnerName());
				long lastPlayed = offlinePlayer.getLastPlayed();
				if ((lastPlayed > 0) && ((System.currentTimeMillis() - lastPlayed) / 86400000 > Settings.playerShopkeeperInactiveDays)) {
					// shop is too old, don't load it
					getLogger().info("Shopkeeper owned by " + playerShop.getOwnerAsString() + " at " + shopkeeper.getPositionString() + " has been removed for owner inactivity");
					continue;
				}
			}

			// add to shopkeepers by chunk
			List<Shopkeeper> list = allShopkeepersByChunk.get(shopkeeper.getChunk());
			if (list == null) {
				list = new ArrayList<Shopkeeper>();
				allShopkeepersByChunk.put(shopkeeper.getChunk(), list);
			}
			list.add(shopkeeper);

			// add to active shopkeepers if spawning not needed
			if (!shopkeeper.needsSpawned()) {
				activeShopkeepers.put(shopkeeper.getId(), shopkeeper);
			}
		}
	}

	void save() {
		if (Settings.saveInstantly) {
			saveReal();
		} else {
			dirty = true;
		}
	}

	private void saveReal() {
		YamlConfiguration config = new YamlConfiguration();
		int counter = 0;
		for (List<Shopkeeper> shopkeepers : allShopkeepersByChunk.values()) {
			for (Shopkeeper shopkeeper : shopkeepers) {
				ConfigurationSection section = config.createSection(counter + "");
				shopkeeper.save(section);
				counter++;
			}
		}

		File file = new File(getDataFolder(), "save.yml");
		if (file.exists()) {
			file.delete();
		}
		try {
			if (Settings.fileEncoding != null && !Settings.fileEncoding.isEmpty()) {
				PrintWriter writer = new PrintWriter(file, Settings.fileEncoding);
				writer.write(config.saveToString());
				writer.close();
			} else {
				config.save(file);
			}
			debug("Saved shopkeeper data");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// checks for missing owner uuids and updates owner names for the shopkeepers of the given player:
	void updateShopkeepersForPlayer(Player player) {
		boolean dirty = false;
		UUID playerUUID = player.getUniqueId();
		String playerName = player.getName();
		for (List<Shopkeeper> shopkeepers : ShopkeepersPlugin.getInstance().allShopkeepersByChunk.values()) {
			for (Shopkeeper shopkeeper : shopkeepers) {
				if (shopkeeper instanceof PlayerShopkeeper) {
					PlayerShopkeeper playerShop = (PlayerShopkeeper) shopkeeper;
					UUID ownerUUID = playerShop.getOwnerUUID();
					String ownerName = playerShop.getOwnerName();

					if (ownerUUID != null) {
						if (playerUUID.equals(ownerUUID)) {
							if (!ownerName.equalsIgnoreCase(playerName)) {
								// update the stored name, because the player must have changed it:
								playerShop.setOwner(player);
								dirty = true;
							}
						}
					} else {
						// we have no uuid for the owner of this shop yet, let's identify the owner by name:
						if (playerName.equalsIgnoreCase(ownerName)) {
							// let's store this player's uuid:
							playerShop.setOwner(player);
							dirty = true;
						}
					}
				}
			}
		}

		if (dirty) {
			this.save();
		}
	}

	public void forceCreatureSpawn(Location location, EntityType entityType) {
		if (creatureForceSpawnListener != null && Settings.bypassSpawnBlocking) {
			creatureForceSpawnListener.forceCreatureSpawn(location, entityType);
		}
	}

	public static ShopkeepersPlugin getInstance() {
		return plugin;
	}

	public static boolean isDebug() {
		return plugin.debug;
	}

	public static void debug(String message) {
		if (plugin.debug) {
			plugin.getLogger().info(message);
		}
	}

	public static void warning(String message) {
		plugin.getLogger().warning(message);
	}

}
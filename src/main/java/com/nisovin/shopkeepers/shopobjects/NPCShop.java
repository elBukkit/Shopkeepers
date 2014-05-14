package com.nisovin.shopkeepers.shopobjects;

import com.nisovin.shopkeepers.Shopkeeper;
import com.nisovin.shopkeepers.ShopkeepersPlugin;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

/**
 * A Shop handler implemented as a Citizens NPC.
 */
public class NPCShop extends ShopObject {
    private Integer npcId;

    protected NPCShop(Shopkeeper shopkeeper) {
        super(shopkeeper);
    }

    protected void checkNPC() {
        if (npcId != null) return;

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.VILLAGER, "Shopkeeper");
        if (npc != null) {
            npcId = npc.getId();
            String worldName = shopkeeper.getWorldName();
            int x = shopkeeper.getX();
            int y = shopkeeper.getY();
            int z = shopkeeper.getZ();
            Location loc = new Location(Bukkit.getWorld(worldName), x, y, z);
            npc.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } else {
            npcId = null;
        }
    }

    @Override
    public void load(ConfigurationSection config) {
        if (config.contains("npcId")) {
            npcId = config.getInt("npcId");
        } else {
            checkNPC();
        }
    }

    @Override
    public void save(ConfigurationSection config) {
        config.set("object", "npc");
        if (npcId != null) {
            config.set("npcId", this.npcId);
        }
    }

    @Override
    public boolean needsSpawned() {
        return false;
    }

    @Override
    public boolean attach(LivingEntity entity) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
        if (npc == null) return false;
        this.npcId = npc.getId();
        return true;
    }

    @Override
    public boolean spawn() {
        return true;
    }

    @Override
    public boolean isActive() {
        return npcId != null;
    }

    @Override
    public String getId() {
        return npcId == null ? "NPC" : "NPC-" + npcId;
    }

    public NPC getNPC() {
        if (npcId == null) return null;
        return CitizensAPI.getNPCRegistry().getById(npcId);
    }

    public LivingEntity getLivingEntity() {
        NPC npc = getNPC();
        return npc != null ? npc.getBukkitEntity() : null;
    }

    @Override
    public Location getActualLocation() {
        LivingEntity entity = getLivingEntity();
        return entity != null ? entity.getLocation() : null;
    }

    @Override
    public void setName(String name) {
        NPC npc = getNPC();
        if (npc != null) {
            npc.setName(name);
        }
    }

    @Override
    public void setItem(ItemStack item) {
        // TODO: No Citizens API for equipping items?
    }

    @Override
    public boolean check() {
        String worldName = shopkeeper.getWorldName();
        int x = shopkeeper.getX();
        int y = shopkeeper.getY();
        int z = shopkeeper.getZ();

        if (!this.isActive()) {
            // Not going to force Citizens creation, this seems like it could go really wrong.
            return true;
        } else {
            World world = Bukkit.getWorld(worldName);
            NPC npc = getNPC();

            if (npc == null) {
                return true;
            }
            Location currentLocation = npc.getStoredLocation();
            float yaw = currentLocation == null ? 0 : currentLocation.getYaw();
            float pitch = currentLocation == null ? 0 : currentLocation.getPitch();
            Location loc = new Location(world, x + .5, y, z + .5, yaw, pitch);
            if (currentLocation == null || currentLocation.distanceSquared(loc) > .4) {
                npc.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                ShopkeepersPlugin.debug("Shopkeeper NPC (" + worldName + "," + x + "," + y + "," + z + ") out of place, teleported back");
            }
            return false;
        }
    }

    @Override
    public void despawn() {
    }

    @Override
    public void delete() {
        NPC npc = getNPC();
        npc.destroy();
        npcId = null;
    }

    @Override
    public ItemStack getTypeItem() {
        // TODO: A menu of entity types here would be cool
        return null;
    }

    @Override
    public ShopObjectType getObjectType() {
        return ShopObjectType.NPC;
    }

    @Override
    public void cycleType() {

    }
}

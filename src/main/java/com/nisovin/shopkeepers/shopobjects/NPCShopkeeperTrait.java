package com.nisovin.shopkeepers.shopobjects;

import com.nisovin.shopkeepers.Shopkeeper;
import com.nisovin.shopkeepers.ShopkeepersPlugin;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.util.DataKey;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * A simple passhtrough Citizens Trait that will be used
 * to hook into an NPCShop.
 */
public class NPCShopkeeperTrait extends Trait {

    private String shopkeeperId;

    public NPCShopkeeperTrait() {
        super("shopkeeper");
    }

    public static void registerTrait() {
        net.citizensnpcs.api.CitizensAPI.getTraitFactory().registerTrait(net.citizensnpcs.api.trait.TraitInfo.create(NPCShopkeeperTrait.class).withName("shopkeeper"));
    }

    public void load(DataKey key) {
        shopkeeperId = key.getString("ShopkeeperId", null);
    }

    public void save(DataKey key) {
        key.setString("ShopkeeperId", shopkeeperId);
    }

    @Override
    public void onAttach() {
        LivingEntity entity = this.getNPC().getBukkitEntity();
        if (entity != null) {
            Location location = entity.getLocation();
            Shopkeeper shopkeeper = ShopkeepersPlugin.getInstance().createNewAdminShopkeeper(location, ShopObjectType.NPC, entity);
            shopkeeperId = shopkeeper == null ? null : shopkeeper.getId();
        }
    }
}

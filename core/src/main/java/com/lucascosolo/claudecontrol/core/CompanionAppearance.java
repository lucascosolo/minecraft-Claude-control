package com.lucascosolo.claudecontrol.core;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/** Native cosmetics on the existing body; dynamic account profiles resolve on the client. */
final class CompanionAppearance {
    private CompanionAppearance(){}
    static void apply(Mannequin body,AppearanceSpec spec) {
        body.setProfile(spec.account().isEmpty()?Mannequin.defaultProfile():
            ResolvableProfile.resolvableProfile().name(spec.account()).build());
        var equipment=body.getEquipment();
        if(spec.preset().equals("plain")) {
            equipment.setHelmet(null);equipment.setChestplate(null);equipment.setLeggings(null);equipment.setBoots(null);return;
        }
        boolean hatter=spec.preset().equals("hatter");
        equipment.setHelmet(dyed(Material.LEATHER_HELMET,hatter?0x8241A8:0x725438));
        equipment.setChestplate(dyed(Material.LEATHER_CHESTPLATE,hatter?0x167F76:0x527342));
        equipment.setLeggings(dyed(Material.LEATHER_LEGGINGS,hatter?0xC49B36:0x725438));
        equipment.setBoots(dyed(Material.LEATHER_BOOTS,0x302838));
    }
    private static ItemStack dyed(Material material,int rgb) {
        ItemStack item=new ItemStack(material);LeatherArmorMeta meta=(LeatherArmorMeta)item.getItemMeta();
        meta.setColor(Color.fromRGB(rgb));item.setItemMeta(meta);return item;
    }
}

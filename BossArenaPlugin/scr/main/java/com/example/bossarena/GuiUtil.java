package com.example.bossarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Мелкие помощники для GUI календаря (Ray095).
 */
final class GuiUtil {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private GuiUtil() {}

    static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    /** Текст с &-цветами без курсива (для названий и описаний предметов). */
    static Component comp(String s) {
        return LEGACY.deserialize(color(s)).decoration(TextDecoration.ITALIC, false);
    }

    /** Текст с &-цветами как есть (для заголовков меню). */
    static Component title(String s) {
        return LEGACY.deserialize(color(s));
    }

    static ItemStack item(Material material, String name, String... lore) {
        List<String> list = new ArrayList<>();
        for (String l : lore) list.add(l);
        return item(material, name, list);
    }

    static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(comp(name));
        if (lore != null && !lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(comp(s));
            meta.lore(l);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    static ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    static ItemStack pane(Material material) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(" "));
        it.setItemMeta(meta);
        return it;
    }

    /** Рамка из двух чередующихся стёкол по краю инвентаря (54 слота). */
    static void frame(Inventory inv, Material a, Material b) {
        ItemStack paneA = pane(a);
        ItemStack paneB = pane(b);
        int rows = inv.getSize() / 9;
        for (int i = 0; i < inv.getSize(); i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) inv.setItem(i, ((r + c) % 2 == 0 ? paneA : paneB).clone());
        }
    }

    static void fillEmpty(Inventory inv, Material material) {
        ItemStack p = pane(material);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, p.clone());
    }
}

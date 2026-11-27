package com.example.bossarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class SummonKeyManager implements Listener {

    private final JavaPlugin plugin;
    private final BossManager bossManager;
    private final CalendarManager calendarManager;
    private final CustomItemsManager customItemsManager;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public SummonKeyManager(JavaPlugin plugin, BossManager bossManager, CalendarManager calendarManager, CustomItemsManager customItemsManager) {
        this.plugin = plugin;
        this.bossManager = bossManager;
        this.calendarManager = calendarManager;
        this.customItemsManager = customItemsManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public ItemStack createKey() {
        return customItemsManager.createItem("boss_summon_key");
    }

    public boolean isKey(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String id = customItemsManager.getCustomItemId(item);
        return "boss_summon_key".equals(id);
    }

    // ★ Запрет на использование ключа испытаний как ванильного ★
    @EventHandler
    public void onTrialKeyUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (isKey(mainHand) || isKey(offHand)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isKey(item)) return;
        event.setCancelled(true);
        openKeyMenu(event.getPlayer());
    }

    private void openKeyMenu(Player player) {
        KeyHolder holder = new KeyHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, LEGACY.deserialize("&6&l🗝 Ключ Призыва — выбор босса"));
        holder.inventory = inv;

        ItemStack pane = pane(Material.ORANGE_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == 5 || c == 0 || c == 8) inv.setItem(i, pane.clone());
        }

        inv.setItem(4, item(Material.ENCHANTED_BOOK, "&6&l🗝 Ключ Призыва",
                "&7Выбери босса для бесплатного вызова.",
                "&7Ключ будет потрачен.",
                "",
                "&c⚠ Не работает за 1 час до календарного босса.",
                "&c⚠ Не работает, если босс уже жив."));

        int slot = 19;
        for (BossRotationManager.BossData b : bossManager.getRotationManager().getBosses()) {
            if (slot >= 44) break;
            String world = (b.spawnPoint != null && b.spawnPoint.location.getWorld() != null)
                    ? b.spawnPoint.location.getWorld().getName() : "?";
            ItemStack icon = item(bossIcon(b.bossType),
                    b.bossName,
                    "&7Мир: &f" + world,
                    "&7HP: &c" + (int) b.bossHealth,
                    "&7Урон: &c" + b.bossDamage,
                    "",
                    "&6ЛКМ: &fпризвать");
            inv.setItem(slot++, icon);
        }

        inv.setItem(49, item(Material.BARRIER, "&c✖ Закрыть"));
        player.openInventory(inv);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 1.2f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof KeyHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) return;

        int slot = event.getSlot();
        if (slot == 49) { player.closeInventory(); return; }

        List<BossRotationManager.BossData> bosses = bossManager.getRotationManager().getBosses();
        int index = slot - 19;
        if (index < 0 || index >= bosses.size()) return;

        BossRotationManager.BossData data = bosses.get(index);

        if (bossManager.isBossAlive()) { player.sendMessage(ChatColor.RED + "❌ Босс уже жив!"); return; }
        if (bossManager.isChestActive()) { player.sendMessage(ChatColor.RED + "❌ Сундук ещё активен!"); return; }
        if (bossManager.isSpawning()) { player.sendMessage(ChatColor.RED + "❌ Босс уже спавнится!"); return; }

        long minutesUntil = calendarManager.getMinutesUntilNextBoss();
        if (minutesUntil >= 0 && minutesUntil <= 60) {
            player.sendMessage(ChatColor.RED + "❌ До календарного босса осталось " + minutesUntil + " мин. Ключ не работает!");
            return;
        }

        boolean spawned = bossManager.spawnBossById(data.id, false);

        if (spawned) {
            ItemStack keyInHand = player.getInventory().getItemInMainHand();
            if (isKey(keyInHand)) {
                if (keyInHand.getAmount() > 1) keyInHand.setAmount(keyInHand.getAmount() - 1);
                else player.getInventory().setItemInMainHand(null);
            }

            player.closeInventory();
            player.sendMessage(ChatColor.GREEN + "✅ Босс " + data.bossName + " призван ключом!");
            Bukkit.broadcast(Component.text(ChatColor.GOLD + "🗝 " + ChatColor.WHITE + player.getName()
                    + ChatColor.GOLD + " призвал босса " + data.bossName + " ключом!"));
        } else {
            player.sendMessage(ChatColor.RED + "❌ Не удалось призвать босса. Ключ не потрачен.");
            player.closeInventory();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof KeyHolder) event.setCancelled(true);
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack it = new ItemStack(material);
        var meta = it.getItemMeta();
        meta.displayName(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', name)).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', s)).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack pane(Material material) {
        ItemStack it = new ItemStack(material);
        var meta = it.getItemMeta();
        meta.displayName(Component.text(" "));
        it.setItemMeta(meta);
        return it;
    }

    private Material bossIcon(org.bukkit.entity.EntityType type) {
        return switch (type) {
            case ZOMBIE -> Material.ZOMBIE_HEAD;
            case SKELETON -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case SPIDER, CAVE_SPIDER -> Material.SPIDER_EYE;
            case ENDER_DRAGON -> Material.DRAGON_HEAD;
            case PIGLIN_BRUTE, PIGLIN -> Material.PIGLIN_HEAD;
            default -> Material.NETHER_STAR;
        };
    }

    private static class KeyHolder implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
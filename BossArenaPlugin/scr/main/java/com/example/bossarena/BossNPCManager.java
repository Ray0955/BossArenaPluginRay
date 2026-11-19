package com.example.bossarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * NPC-витрина: житель с эффектами, по ПКМ открывает красивое меню со всем возможным лутом
 * (страницы, редкость, шансы, мифические предметы светятся) (Ray095).
 *
 * Меню нельзя ничего вытащить или положить: все клики отменяются.
 */
public class BossNPCManager implements Listener {

    private static final String TAG_NPC = "boss_npc";
    private static final String TAG_HOLO = "boss_npc_holo";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final JavaPlugin plugin;
    private final BossManager bossManager;

    private Villager npc;
    private ArmorStand hologram;
    private BukkitTask task;
    private double angle = 0;
    private int tick = 0;

    public BossNPCManager(JavaPlugin plugin, BossManager bossManager) {
        this.plugin = plugin;
        this.bossManager = bossManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        loadNPCFromConfig();
    }

    // ───────────────────────── Управление NPC ─────────────────────────

    /** Перечитать конфиг и пересоздать NPC. */
    public void loadNPCFromConfig() {
        spawnNPC();
        if (task == null) startTask();
    }

    /** /bossnpc set - поставить NPC на место игрока (сохраняется в конфиг). */
    public void setNPCLocation(Player player) {
        removeEntities();
        Location l = player.getLocation();
        var c = plugin.getConfig();
        c.set("boss_npc.location.world", l.getWorld().getName());
        c.set("boss_npc.location.x", l.getBlockX() + 0.5);
        c.set("boss_npc.location.y", l.getY());
        c.set("boss_npc.location.z", l.getBlockZ() + 0.5);
        c.set("boss_npc.location.yaw", (double) ((l.getYaw() + 180f) % 360f));
        c.set("boss_npc.location.pitch", 0.0);
        c.set("boss_npc.location.placed", true);
        plugin.saveConfig();
        spawnNPC();
        if (task == null) startTask();
        player.sendMessage(ChatColor.GREEN + "✅ NPC-витрина установлена здесь!");
    }

    /** /bossnpc remove - убрать NPC навсегда (до следующего /bossnpc set). */
    public void removeNPC() {
        removeEntities();
        plugin.getConfig().set("boss_npc.location.placed", false);
        plugin.saveConfig();
    }

    /** Остановка плагина: убрать сущности, ничего не меняя в конфиге. */
    public void cleanup() {
        if (task != null) { task.cancel(); task = null; }
        removeEntities();
    }

    public boolean isAvailable() {
        return plugin.getConfig().getBoolean("boss_npc.available", true);
    }

    public void setAvailable(boolean available) {
        plugin.getConfig().set("boss_npc.available", available);
        plugin.saveConfig();
        refreshHologram();
    }

    private Location getNPCLocation() {
        var c = plugin.getConfig();
        if (!c.getBoolean("boss_npc.location.placed", false)) return null;
        World w = Bukkit.getWorld(c.getString("boss_npc.location.world", "world"));
        if (w == null) return null;
        return new Location(w,
                c.getDouble("boss_npc.location.x"),
                c.getDouble("boss_npc.location.y", 64),
                c.getDouble("boss_npc.location.z"),
                (float) c.getDouble("boss_npc.location.yaw"),
                (float) c.getDouble("boss_npc.location.pitch"));
    }

    private void spawnNPC() {
        removeEntities();
        if (!plugin.getConfig().getBoolean("boss_npc.enabled", true)) return;
        Location loc = getNPCLocation();
        if (loc == null) return;
        World w = loc.getWorld();
        String name = plugin.getConfig().getString("boss_npc.name", "&6&l📦 Ресурсы с боссов");

        npc = w.spawn(loc, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setCollidable(false);
            v.setPersistent(false);          // не сохраняется в мир, пересоздаётся плагином
            v.setRemoveWhenFarAway(false);
            v.setProfession(Villager.Profession.LIBRARIAN);
            v.setVillagerLevel(5);
            v.customName(comp(name));
            v.setCustomNameVisible(true);
            v.addScoreboardTag(TAG_NPC);
        });

        hologram = w.spawn(loc.clone().add(0, 2.55, 0), ArmorStand.class, a -> {
            a.setVisible(false);
            a.setGravity(false);
            a.setMarker(true);
            a.setSmall(true);
            a.setInvulnerable(true);
            a.setPersistent(false);
            a.setCustomNameVisible(true);
            a.addScoreboardTag(TAG_HOLO);
        });
        refreshHologram();
    }

    private void removeEntities() {
        if (npc != null) { npc.remove(); npc = null; }
        if (hologram != null) { hologram.remove(); hologram = null; }
        Location loc = getNPCLocation();
        if (loc != null && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            for (Entity e : loc.getWorld().getNearbyEntities(loc, 3, 5, 3)) {
                if (e.getScoreboardTags().contains(TAG_NPC) || e.getScoreboardTags().contains(TAG_HOLO)) e.remove();
            }
        }
    }

    private void refreshHologram() {
        if (hologram == null || !hologram.isValid()) return;
        hologram.customName(comp(isAvailable()
                ? "&e&l▶ &fНажми &eПКМ &e&l◀"
                : "&c&l🔧 Тех. работы"));
    }

    private boolean isNpcEntity(Entity e) {
        return e != null && (e.getScoreboardTags().contains(TAG_NPC) || e.getScoreboardTags().contains(TAG_HOLO));
    }

    // ───────────────────────── Эффекты ─────────────────────────

    private void startTask() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickNPC, 40L, 5L);
    }

    private void tickNPC() {
        if (!plugin.getConfig().getBoolean("boss_npc.enabled", true)) return;
        Location loc = getNPCLocation();
        if (loc == null) return;
        World w = loc.getWorld();
        if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;

        // NPC пропал (выгрузка чанка и т.п.) - пересоздаём
        if (npc == null || !npc.isValid() || hologram == null || !hologram.isValid()) spawnNPC();
        if (npc == null) return;

        Collection<Player> near = w.getNearbyPlayers(loc, 24);
        if (near.isEmpty()) return;

        // смотрит на ближайшего игрока
        Player closest = null;
        double best = 36; // 6 блоков
        for (Player p : near) {
            double d = p.getLocation().distanceSquared(npc.getLocation());
            if (d < best) { best = d; closest = p; }
        }
        if (closest != null) {
            Location l = npc.getLocation();
            Vector dir = closest.getLocation().toVector().subtract(l.toVector());
            l.setDirection(dir);
            npc.setRotation(l.getYaw(), l.getPitch());
        }

        // две светящиеся спирали вокруг NPC + магические частицы
        angle += 0.45;
        Location base = npc.getLocation();
        for (int i = 0; i < 2; i++) {
            double a = angle + i * Math.PI;
            double h = 1.0 + 0.9 * Math.sin(angle * 0.5 + i);
            w.spawnParticle(Particle.END_ROD,
                    base.getX() + Math.cos(a) * 0.9, base.getY() + h, base.getZ() + Math.sin(a) * 0.9,
                    1, 0, 0, 0, 0);
        }
        if (tick++ % 4 == 0) {
            w.spawnParticle(Particle.ENCHANT, base.clone().add(0, 1.5, 0), 12, 0.6, 0.6, 0.6, 0.5);
        }
    }

    // ───────────────────────── События ─────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!isNpcEntity(event.getRightClicked())) return;
        event.setCancelled(true);                       // чтобы не открылась торговля
        if (event.getHand() != EquipmentSlot.HAND) return;
        openShowcase(event.getPlayer());
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (isNpcEntity(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShowcaseHolder holder)) return;
        event.setCancelled(true);                       // ничего нельзя взять или положить
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;

        int slot = event.getSlot();
        if (slot == 45 && holder.page > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
                openPage(player, holder.items, holder.page - 1);
            });
        } else if (slot == 53 && holder.page < holder.pages - 1) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.1f);
                openPage(player, holder.items, holder.page + 1);
            });
        } else if (slot == 49) {
            Bukkit.getScheduler().runTask(plugin, player::closeInventory);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShowcaseHolder) event.setCancelled(true);
    }

    // ───────────────────────── Витрина (GUI) ─────────────────────────

    private void openShowcase(Player player) {
        if (!player.hasPermission("bossarena.npc.use")) {
            player.sendMessage(ChatColor.RED + "❌ У вас нет доступа к витрине!");
            return;
        }
        if (!isAvailable()
                && !player.hasPermission("bossarena.npc.use.disabled")
                && !player.hasPermission("bossarena.npc.bypass")) {
            player.sendMessage(color(plugin.getConfig().getString("boss_npc.disabled_message",
                    "&c⚠️ Витрина недоступна! Тех. работы.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        List<ItemStack> items = buildDisplayItems();
        openPage(player, items, 0);
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 1.2f);

        String click = plugin.getConfig().getString("boss_npc.click_message", "");
        if (click != null && !click.isEmpty()) player.sendActionBar(comp(click));
    }

    /** Все возможные предметы: сначала мифические, дальше от редких к обычным. */
    private List<ItemStack> buildDisplayItems() {
        LootManager lm = new LootManager(plugin);
        boolean partial = plugin.getConfig().getInt("chest.guaranteed_max", 0) > 0;

        List<LootManager.LootItem> customs = new ArrayList<>(lm.getCustomLootItems());
        customs.sort(Comparator.comparingDouble(i -> i.chance));
        List<LootManager.LootItem> mats = new ArrayList<>(lm.getLootItems());
        mats.sort(Comparator.comparingDouble(i -> i.chance));

        List<ItemStack> result = new ArrayList<>();
        for (LootManager.LootItem li : customs) {
            ItemStack s = buildDisplay(li, partial);
            if (s != null) result.add(s);
        }
        for (LootManager.LootItem li : mats) {
            ItemStack s = buildDisplay(li, partial);
            if (s != null) result.add(s);
        }
        return result;
    }

    private ItemStack buildDisplay(LootManager.LootItem li, boolean partial) {
        ItemStack stack = li.isCustom
                ? bossManager.getCustomItemsManager().createItem(li.itemId)
                : new ItemStack(li.material);
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;

        double c = li.chance;
        boolean mythic = li.isCustom || c < 1.0;

        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(comp("&8▪ &7Шанс: " + rarityColor(c, li.isCustom) + fmt(c) + "%"));
        lore.add(comp("&8▪ &7Количество: &f"
                + (li.minAmount == li.maxAmount ? String.valueOf(li.minAmount) : li.minAmount + "-" + li.maxAmount)));
        lore.add(comp("&8▪ &7Редкость: " + rarityName(c, li.isCustom)));
        if (c >= 100.0 && partial) lore.add(comp("&8▪ &7В сундуке выпадает лишь часть таких"));
        meta.lore(lore);

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (mythic) meta.setEnchantmentGlintOverride(true);   // светятся предметы с шансом < 1%
        stack.setItemMeta(meta);
        return stack;
    }

    private void openPage(Player player, List<ItemStack> items, int page) {
        var cfg = plugin.getConfig();
        int perRow = Math.max(1, Math.min(7, cfg.getInt("boss_npc.showcase.items_per_row", 7)));
        int capacity = perRow * 4;                       // 4 внутренних ряда
        int pages = Math.max(1, (items.size() + capacity - 1) / capacity);
        page = Math.max(0, Math.min(page, pages - 1));

        String baseTitle = cfg.getString("boss_npc.showcase.title", "&6&l📦 Ресурсы с боссов");
        ShowcaseHolder holder = new ShowcaseHolder(items, page, pages);
        Inventory inv = Bukkit.createInventory(holder, 54,
                LEGACY.deserialize(color(baseTitle + " &8(" + (page + 1) + "/" + pages + ")")));
        holder.inventory = inv;

        // рамка из чередующихся стёкол
        ItemStack paneA = pane(Material.PURPLE_STAINED_GLASS_PANE);
        ItemStack paneB = pane(Material.MAGENTA_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) {
            int r = i / 9, c = i % 9;
            if (r == 0 || r == 5 || c == 0 || c == 8) inv.setItem(i, ((r + c) % 2 == 0 ? paneA : paneB).clone());
        }

        // предметы по центру внутренней области
        int startCol = 1 + (7 - perRow) / 2;
        int from = page * capacity;
        for (int idx = 0; idx < capacity; idx++) {
            int itemIndex = from + idx;
            if (itemIndex >= items.size()) break;
            int row = 1 + idx / perRow;
            int col = startCol + idx % perRow;
            inv.setItem(row * 9 + col, items.get(itemIndex).clone());
        }

        inv.setItem(4, infoItem());
        if (page > 0) inv.setItem(45, button(Material.ARROW, "&e◀ Назад", "&7Страница " + page + "/" + pages));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "&eВперёд ▶", "&7Страница " + (page + 2) + "/" + pages));
        inv.setItem(49, button(Material.BARRIER, "&c✖ Закрыть"));

        ItemStack dark = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) if (inv.getItem(i) == null) inv.setItem(i, dark.clone());

        player.openInventory(inv);
    }

    private ItemStack infoItem() {
        var cfg = plugin.getConfig();
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(comp(cfg.getString("boss_npc.info.title", "&6&l📖 ВАЖНО!")));
        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getStringList("boss_npc.info.lore")) lore.add(comp(line));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(comp(name));
        if (loreLines.length > 0) {
            List<Component> lore = new ArrayList<>();
            for (String l : loreLines) lore.add(comp(l));
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    // ───────────────────────── Вспомогательное ─────────────────────────

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private Component comp(String s) {
        return LEGACY.deserialize(color(s)).decoration(TextDecoration.ITALIC, false);
    }

    private String fmt(double c) {
        String s = String.format(Locale.US, "%.2f", c);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    private String rarityColor(double c, boolean custom) {
        if (custom || c < 1.0) return "&d&l";
        if (c < 10.0) return "&6";
        if (c < 40.0) return "&5";
        if (c < 100.0) return "&9";
        return "&7";
    }

    private String rarityName(double c, boolean custom) {
        if (custom || c < 1.0) return "&d&l★ МИФИЧЕСКИЙ ★";
        if (c < 10.0) return "&6&lЛегендарный";
        if (c < 40.0) return "&5Эпический";
        if (c < 100.0) return "&9Редкий";
        return "&7Обычный";
    }

    private static class ShowcaseHolder implements InventoryHolder {
        final List<ItemStack> items;
        final int page;
        final int pages;
        Inventory inventory;

        ShowcaseHolder(List<ItemStack> items, int page, int pages) {
            this.items = items;
            this.page = page;
            this.pages = pages;
        }

        @Override
        public Inventory getInventory() { return inventory; }
    }
}

package com.example.bossarena;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class BagManager implements Listener {

    private final JavaPlugin plugin;
    private final CustomItemsManager customItemsManager;
    private final NamespacedKey bagKey;
    private final NamespacedKey bagItemsKey;

    public BagManager(JavaPlugin plugin, CustomItemsManager customItemsManager) {
        this.plugin = plugin;
        this.customItemsManager = customItemsManager;
        this.bagKey = new NamespacedKey(plugin, "custom_bag");
        this.bagItemsKey = new NamespacedKey(plugin, "bag_items");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public ItemStack createBag() {
        ItemStack bag = customItemsManager.createItem("custom_bag");
        if (bag == null) return null;
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return null;
        List<String> lore = new ArrayList<>();
        int maxItems = getMaxItems();
        for (String line : meta.getLore()) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line.replace("{MAX_ITEMS}", String.valueOf(maxItems))));
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(bagKey, PersistentDataType.INTEGER, 1);
        bag.setItemMeta(meta);
        return bag;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || !isBag(item)) return;
        event.setCancelled(true);
        openBag(event.getPlayer(), item);
    }

    private void openBag(Player player, ItemStack bagItem) {
        int size = getSize();
        BagHolder holder = new BagHolder(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, size, ChatColor.DARK_PURPLE + "🎒 Сумка кастомных предметов");
        holder.inventory = inv;
        String serialized = bagItem.getItemMeta().getPersistentDataContainer().get(bagItemsKey, PersistentDataType.STRING);
        if (serialized != null && !serialized.isEmpty()) deserializeInto(inv, serialized);
        player.openInventory(inv);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof BagHolder holder)) return;

        Inventory inv = event.getInventory();
        // ★ Сохраняем в сумку ВЛАДЕЛЬЦА ★
        UUID ownerId = holder.getOwner();
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) return;

        for (ItemStack item : owner.getInventory().getContents()) {
            if (isBag(item)) {
                saveToBag(item, inv);
                break;
            }
        }
    }

    private void saveToBag(ItemStack bagItem, Inventory inv) {
        ItemMeta meta = bagItem.getItemMeta();
        if (meta == null) return;
        StringBuilder sb = new StringBuilder();
        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType().isAir()) sb.append("empty;");
            else sb.append(serializeItem(item)).append(";");
        }
        meta.getPersistentDataContainer().set(bagItemsKey, PersistentDataType.STRING, sb.toString());
        bagItem.setItemMeta(meta);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof BagHolder)) return;

        boolean allowVanilla = plugin.getConfig().getBoolean("custom_items.custom_bag.bag_allow_vanilla", false);
        if (!allowVanilla) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir() && !customItemsManager.isAllowedInBag(cursor)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "❌ В сумку можно класть только предметы из лута (custom:)!");
            }
        }
        if (event.getCurrentItem() != null && isBag(event.getCurrentItem())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "❌ Сумку нельзя положить в сумку!");
        }
        int maxItems = getMaxItems();
        int currentCount = countItems(event.getInventory());
        if (currentCount >= maxItems && event.getCursor() != null && !event.getCursor().getType().isAir()) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "❌ Сумка заполнена! Максимум: " + maxItems);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof BagHolder)) return;
        boolean allowVanilla = plugin.getConfig().getBoolean("custom_items.custom_bag.bag_allow_vanilla", false);
        if (!allowVanilla) {
            for (ItemStack item : event.getNewItems().values()) {
                if (item != null && !item.getType().isAir() && !customItemsManager.isAllowedInBag(item)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    private String serializeItem(ItemStack item) {
        try { return Base64.getEncoder().encodeToString(item.serializeAsBytes()); }
        catch (Exception e) { return ""; }
    }

    private void deserializeInto(Inventory inv, String serialized) {
        String[] parts = serialized.split(";");
        int slot = 0;
        for (String part : parts) {
            if (slot >= inv.getSize()) break;
            if (part.equals("empty") || part.isEmpty()) { slot++; continue; }
            try {
                byte[] bytes = Base64.getDecoder().decode(part);
                ItemStack item = ItemStack.deserializeBytes(bytes);
                inv.setItem(slot, item);
            } catch (Exception ignored) {}
            slot++;
        }
    }

    public boolean isBag(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(bagKey, PersistentDataType.INTEGER);
    }

    public List<ItemStack> getBagContents(ItemStack bagItem) {
        List<ItemStack> result = new ArrayList<>();
        if (bagItem == null || !isBag(bagItem)) return result;
        ItemMeta meta = bagItem.getItemMeta();
        if (meta == null) return result;
        String serialized = meta.getPersistentDataContainer().get(bagItemsKey, PersistentDataType.STRING);
        if (serialized == null || serialized.isEmpty()) return result;
        String[] parts = serialized.split(";");
        for (String part : parts) {
            if (part.equals("empty") || part.isEmpty()) continue;
            try {
                byte[] bytes = Base64.getDecoder().decode(part);
                ItemStack item = ItemStack.deserializeBytes(bytes);
                if (item != null && !item.getType().isAir()) result.add(item);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private int getSize() {
        int size = plugin.getConfig().getInt("custom_items.custom_bag.bag_size", 27);
        return Math.max(9, Math.min(54, (size / 9) * 9));
    }

    private int getMaxItems() {
        return plugin.getConfig().getInt("custom_items.custom_bag.bag_max_items", 27);
    }

    private int countItems(Inventory inv) {
        int count = 0;
        for (ItemStack item : inv.getContents()) if (item != null && !item.getType().isAir()) count++;
        return count;
    }

    public boolean openBagForAdmin(Player admin, Player target) {
        for (ItemStack item : target.getInventory().getContents()) {
            if (isBag(item)) {
                openBagForAdmin(admin, target, item);
                return true;
            }
        }
        return false;
    }

    private void openBagForAdmin(Player admin, Player target, ItemStack bagItem) {
        int size = getSize();
        BagHolder holder = new BagHolder(target.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, size, ChatColor.DARK_PURPLE + "🎒 Сумка: " + target.getName());
        holder.inventory = inv;
        String serialized = bagItem.getItemMeta().getPersistentDataContainer().get(bagItemsKey, PersistentDataType.STRING);
        if (serialized != null && !serialized.isEmpty()) deserializeInto(inv, serialized);
        admin.openInventory(inv);
    }

    public static class BagHolder implements InventoryHolder {
        private final UUID owner;
        public Inventory inventory;
        public BagHolder(UUID owner) { this.owner = owner; }
        public UUID getOwner() { return owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
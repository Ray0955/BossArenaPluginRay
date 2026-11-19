package com.example.bossarena;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Кастомные предметы: загрузка, баффы, поддержка уровней эффектов (Ray095).
 */
public class CustomItemsManager {

    private final JavaPlugin plugin;
    private final Map<String, CustomItem> customItems = new HashMap<>();
    private final Map<UUID, Map<String, Double>> playerActiveBuffs = new HashMap<>();
    private final Map<UUID, Set<PotionEffectType>> playerActiveEffects = new HashMap<>();
    private final NamespacedKey customItemKey;

    public CustomItemsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.customItemKey = new NamespacedKey(plugin, "custom_item");
        loadCustomItems();
        startBuffChecker();
    }

    public void loadCustomItems() {
        customItems.clear();
        ConfigurationSection itemsSection = plugin.getConfig().getConfigurationSection("custom_items");
        if (itemsSection == null) return;

        for (String key : itemsSection.getKeys(false)) {
            try {
                ConfigurationSection itemConfig = itemsSection.getConfigurationSection(key);
                if (!itemConfig.getBoolean("enabled", true)) continue;

                Material material = Material.getMaterial(itemConfig.getString("material", "SLIME_BALL").toUpperCase());
                if (material == null) continue;

                String name = ChatColor.translateAlternateColorCodes('&', itemConfig.getString("name", "&aПредмет"));
                List<String> lore = new ArrayList<>();
                for (String line : itemConfig.getStringList("lore")) lore.add(ChatColor.translateAlternateColorCodes('&', line));

                Map<Attribute, Double> attributes = new HashMap<>();
                ConfigurationSection statsSection = itemConfig.getConfigurationSection("stats");
                if (statsSection != null) {
                    for (String statKey : statsSection.getKeys(false)) {
                        try {
                            attributes.put(Attribute.valueOf(statKey.toUpperCase()), statsSection.getDouble(statKey, 0.0));
                        } catch (IllegalArgumentException ignored) {}
                    }
                }

                Map<String, int[]> potionEffects = new LinkedHashMap<>();
                ConfigurationSection effectsSection = itemConfig.getConfigurationSection("potion_effects");
                if (effectsSection != null) {
                    for (String effectKey : effectsSection.getKeys(false)) {
                        ConfigurationSection effectConfig = effectsSection.getConfigurationSection(effectKey);
                        if (effectConfig != null) {
                            int duration = effectConfig.getInt("duration", 10);
                            int level = effectConfig.getInt("level", 1);
                            potionEffects.put(effectKey, new int[]{duration, level});
                        } else {
                            int duration = effectsSection.getInt(effectKey, 10);
                            potionEffects.put(effectKey, new int[]{duration, 1});
                        }
                    }
                }

                CustomItem customItem = new CustomItem(
                        key, material, name, lore, attributes, potionEffects,
                        itemConfig.getBoolean("glow", false),
                        itemConfig.getBoolean("unbreakable", false)
                );

                customItems.put(key, customItem);
            } catch (Exception e) {
                plugin.getLogger().warning("❌ Ошибка загрузки " + key + ": " + e.getMessage());
            }
        }
    }

    public ItemStack createItem(String itemId) {
        CustomItem customItem = customItems.get(itemId);
        if (customItem == null) return null;

        ItemStack item = new ItemStack(customItem.material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        meta.setDisplayName(customItem.name);
        meta.setLore(customItem.lore);

        if (customItem.glow) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        if (customItem.unbreakable) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.addItemFlags(ItemFlag.HIDE_POTION_EFFECTS);
        meta.getPersistentDataContainer().set(customItemKey, PersistentDataType.STRING, customItem.id);

        // модель-голова из ресурспака ElitWorld_CustomHeads (отключается: resource_pack.enabled: false)
        if (plugin.getConfig().getBoolean("resource_pack.enabled", true)) {
            String ns = plugin.getConfig().getString("resource_pack.namespace", "elitworld");
            meta.setItemModel(new NamespacedKey(ns, customItem.id));
        }

        item.setItemMeta(meta);
        return item;
    }

    public boolean isCustomItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(customItemKey, PersistentDataType.STRING);
    }

    public String getCustomItemId(ItemStack item) {
        if (!isCustomItem(item)) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(customItemKey, PersistentDataType.STRING);
    }

    public List<CustomItem> getCustomItemsInInventory(Player player) {
        List<CustomItem> result = new ArrayList<>();
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCustomItem(item)) {
                String id = getCustomItemId(item);
                if (id != null && customItems.containsKey(id)) result.add(customItems.get(id));
            }
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (isCustomItem(item)) {
                String id = getCustomItemId(item);
                if (id != null && customItems.containsKey(id)) result.add(customItems.get(id));
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (isCustomItem(offhand)) {
            String id = getCustomItemId(offhand);
            if (id != null && customItems.containsKey(id)) result.add(customItems.get(id));
        }
        return result;
    }

    public void applyAllBuffs(Player player) {
        clearPlayerBuffs(player);
        List<CustomItem> items = getCustomItemsInInventory(player);
        if (items.isEmpty()) return;

        Map<String, Double> activeBuffs = new HashMap<>();
        Set<PotionEffectType> activeEffects = new HashSet<>();

        for (CustomItem item : items) {
            for (Map.Entry<Attribute, Double> entry : item.attributes.entrySet()) {
                String key = entry.getKey().name();
                activeBuffs.put(key, activeBuffs.getOrDefault(key, 0.0) + entry.getValue());
                applyAttributeModifier(player, entry.getKey(), entry.getValue());
            }

            for (Map.Entry<String, int[]> effectEntry : item.potionEffects.entrySet()) {
                try {
                    PotionEffectType effectType = PotionEffectType.getByName(effectEntry.getKey().toUpperCase());
                    if (effectType != null) {
                        int duration = effectEntry.getValue()[0];
                        int level = effectEntry.getValue()[1];
                        int amplifier = Math.max(0, level - 1);
                        
                        player.addPotionEffect(new PotionEffect(
                                effectType, duration * 20, amplifier, true, false));
                        activeEffects.add(effectType);
                    }
                } catch (Exception ignored) {}
            }
        }

        playerActiveBuffs.put(player.getUniqueId(), activeBuffs);
        playerActiveEffects.put(player.getUniqueId(), activeEffects);
    }

    private void applyAttributeModifier(Player player, Attribute attribute, double value) {
        removeAttributeModifier(player, attribute);
        NamespacedKey key = new NamespacedKey(plugin, "custom_buff_" + attribute.name().toLowerCase());
        var attr = player.getAttribute(attribute);
        if (attr == null) return;
        attr.addModifier(new AttributeModifier(
                key, value, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    private void removeAttributeModifier(Player player, Attribute attribute) {
        if (player.getAttribute(attribute) == null) return;
        NamespacedKey key = new NamespacedKey(plugin, "custom_buff_" + attribute.name().toLowerCase());
        player.getAttribute(attribute).removeModifier(key);
    }

    public void clearPlayerBuffs(Player player) {
        UUID playerId = player.getUniqueId();
        playerActiveBuffs.remove(playerId);

        Set<PotionEffectType> effects = playerActiveEffects.get(playerId);
        if (effects != null) {
            for (PotionEffectType effect : effects) {
                player.removePotionEffect(effect);
            }
        }
        playerActiveEffects.remove(playerId);

        for (Attribute attribute : Attribute.values()) removeAttributeModifier(player, attribute);
        player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
    }

    private void startBuffChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    List<CustomItem> currentItems = getCustomItemsInInventory(player);
                    Map<String, Double> currentBuffs = new HashMap<>();
                    for (CustomItem item : currentItems) {
                        for (Map.Entry<Attribute, Double> entry : item.attributes.entrySet()) {
                            String key = entry.getKey().name();
                            currentBuffs.put(key, currentBuffs.getOrDefault(key, 0.0) + entry.getValue());
                        }
                    }
                    Map<String, Double> previousBuffs = playerActiveBuffs.getOrDefault(player.getUniqueId(), new HashMap<>());
                    boolean needRefresh = !currentBuffs.equals(previousBuffs);
                    
                    if (!needRefresh) {
                        Set<PotionEffectType> activeEffects = playerActiveEffects.getOrDefault(player.getUniqueId(), new HashSet<>());
                        if (currentItems.isEmpty() && !activeEffects.isEmpty()) needRefresh = true;
                    }
                    if (needRefresh) applyAllBuffs(player);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void refreshAllBuffs() {
        for (Player player : Bukkit.getOnlinePlayers()) applyAllBuffs(player);
    }

    public Map<String, CustomItem> getCustomItems() { return customItems; }
    public CustomItem getCustomItem(String id) { return customItems.get(id); }

    public static class CustomItem {
        public final String id;
        public final Material material;
        public final String name;
        public final List<String> lore;
        public final Map<Attribute, Double> attributes;
        public final Map<String, int[]> potionEffects;
        public final boolean glow;
        public final boolean unbreakable;

        public CustomItem(String id, Material material, String name, List<String> lore,
                          Map<Attribute, Double> attributes, Map<String, int[]> potionEffects,
                          boolean glow, boolean unbreakable) {
            this.id = id;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.attributes = attributes;
            this.potionEffects = potionEffects;
            this.glow = glow;
            this.unbreakable = unbreakable;
        }
    }
}

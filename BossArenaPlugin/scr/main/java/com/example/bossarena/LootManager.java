package com.example.bossarena;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class LootManager {

    private final JavaPlugin plugin;
    private final List<LootItem> lootItems = new ArrayList<>();
    private final List<LootItem> customLootItems = new ArrayList<>();
    private final Map<Material, Double> materialChance = new HashMap<>();
    private final Random random = new Random();
    private final CustomItemsManager customItemsManager;

    private double lootBonus = 0.0;

    private static final Map<String, String> ALIASES = Map.of("SEEDS", "WHEAT_SEEDS");

    public LootManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.customItemsManager = ((BossArena) plugin).getBossManager().getCustomItemsManager();
        loadGlobalLoot();
    }

    public void setLootBonus(double bonus) { this.lootBonus = bonus; }

    public void loadGlobalLoot() {
        lootItems.clear();
        customLootItems.clear();
        materialChance.clear();
        List<String> lootConfig = plugin.getConfig().getStringList("global_loot");
        if (lootConfig.isEmpty()) return;

        for (String entry : lootConfig) {
            try {
                String[] parts = entry.split(":");
                if (entry.startsWith("custom:")) {
                    if (parts.length < 4) { warnBad(entry); continue; }
                    String itemId = parts[1];
                    if (customItemsManager.getCustomItem(itemId) == null) {
                        plugin.getLogger().warning("❌ Лут: кастомный предмет '" + itemId + "' не найден: " + entry);
                        continue;
                    }
                    int[] range = parseAmount(parts[2]);
                    double chance = Double.parseDouble(parts[3].replace("%", ""));
                    customLootItems.add(new LootItem(null, range[0], range[1], chance, itemId, true));
                } else {
                    if (parts.length < 3) { warnBad(entry); continue; }
                    String name = parts[0].toUpperCase();
                    name = ALIASES.getOrDefault(name, name);
                    Material material = Material.getMaterial(name);
                    if (material == null || material.isAir()) {
                        plugin.getLogger().warning("❌ Лут: неизвестный предмет '" + parts[0] + "': " + entry);
                        continue;
                    }
                    int[] range = parseAmount(parts[1]);
                    double chance = Double.parseDouble(parts[2].replace("%", ""));
                    lootItems.add(new LootItem(material, range[0], range[1], chance, null, false));
                    materialChance.merge(material, chance, Math::min);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("❌ Лут: ошибка в строке '" + entry + "': " + e.getMessage());
            }
        }
    }

    private void warnBad(String entry) { plugin.getLogger().warning("❌ Лут: неверный формат строки: " + entry); }

    private int[] parseAmount(String s) {
        s = s.trim();
        int min, max;
        if (s.contains("-")) {
            String[] r = s.split("-");
            min = Integer.parseInt(r[0].trim());
            max = Integer.parseInt(r[1].trim());
        } else min = max = Integer.parseInt(s);
        if (min < 1) min = 1;
        if (max < min) max = min;
        return new int[]{min, max};
    }

    public List<ItemStack> generateLoot() {
        List<ItemStack> rewards = new ArrayList<>();
        List<LootItem> guaranteed = new ArrayList<>();
        for (LootItem item : lootItems) {
            if (item.chance >= 100.0) { guaranteed.add(item); continue; }
            double effectiveChance = Math.min(100.0, item.chance + lootBonus);
            if (random.nextDouble() * 100 <= effectiveChance) {
                int amount = item.minAmount + random.nextInt(item.maxAmount - item.minAmount + 1);
                addSplit(rewards, item.material, amount);
            }
        }
        int gMax = plugin.getConfig().getInt("chest.guaranteed_max", 0);
        int gMin = plugin.getConfig().getInt("chest.guaranteed_min", 1);
        int take = guaranteed.size();
        if (gMax > 0 && !guaranteed.isEmpty()) {
            gMax = Math.min(gMax, guaranteed.size());
            gMin = Math.max(1, Math.min(gMin, gMax));
            take = gMin + random.nextInt(gMax - gMin + 1);
            Collections.shuffle(guaranteed, random);
        }
        for (int i = 0; i < take && i < guaranteed.size(); i++) {
            LootItem item = guaranteed.get(i);
            int amount = item.minAmount + random.nextInt(item.maxAmount - item.minAmount + 1);
            addSplit(rewards, item.material, amount);
        }
        List<LootItem> droppedCustom = new ArrayList<>();
        for (LootItem item : customLootItems) {
            double effectiveChance = Math.min(100.0, item.chance + lootBonus);
            if (random.nextDouble() * 100 <= effectiveChance) droppedCustom.add(item);
        }
        if (!droppedCustom.isEmpty()) {
            LootItem selected = droppedCustom.get(random.nextInt(droppedCustom.size()));
            ItemStack customStack = customItemsManager.createItem(selected.itemId);
            if (customStack != null) { customStack.setAmount(1); rewards.add(customStack); }
        }
        return rewards;
    }

    private void addSplit(List<ItemStack> out, Material material, int amount) {
        int maxStack = Math.max(1, material.getMaxStackSize());
        while (amount > 0) {
            int part = Math.min(amount, maxStack);
            out.add(new ItemStack(material, part));
            amount -= part;
        }
    }

    public void fillChest(Inventory chest, List<ItemStack> items) {
        int maxSlots = plugin.getConfig().getInt("chest.max_slots", 35);
        int minEmptySlots = plugin.getConfig().getInt("chest.min_empty_slots", 10);
        chest.clear();
        int slotsToUse = Math.min(maxSlots, chest.getSize()) - minEmptySlots;
        if (slotsToUse <= 0) return;
        List<ItemStack> toPlace = new ArrayList<>(items);
        Collections.shuffle(toPlace, random);
        toPlace.sort(Comparator.comparingDouble(this::rarityOf));
        if (toPlace.size() > slotsToUse) toPlace = new ArrayList<>(toPlace.subList(0, slotsToUse));
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < chest.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, random);
        for (int i = 0; i < toPlace.size() && i < slots.size(); i++) chest.setItem(slots.get(i), toPlace.get(i));
    }

    private double rarityOf(ItemStack stack) {
        if (customItemsManager.isCustomItem(stack)) return -1.0;
        return materialChance.getOrDefault(stack.getType(), 100.0);
    }

    public List<LootItem> getLootItems() { return lootItems; }
    public List<LootItem> getCustomLootItems() { return customLootItems; }

    public static class LootItem {
        public final Material material;
        public final int minAmount;
        public final int maxAmount;
        public final double chance;
        public final String itemId;
        public final boolean isCustom;

        public LootItem(Material material, int minAmount, int maxAmount, double chance, String itemId, boolean isCustom) {
            this.material = material;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.chance = chance;
            this.itemId = itemId;
            this.isCustom = isCustom;
        }
    }
}
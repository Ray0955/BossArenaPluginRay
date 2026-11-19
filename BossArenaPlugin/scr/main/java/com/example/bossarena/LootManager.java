package com.example.bossarena;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Генерация лута (Ray095).
 *
 * Формат строки global_loot:
 *   МАТЕРИАЛ:МИН-МАКС:ШАНС   - случайное количество (например DIAMOND:10-20:40.0)
 *   МАТЕРИАЛ:КОЛИЧЕСТВО:ШАНС - ровно столько (например IRON_SWORD:1:20.0)
 *   custom:ID:КОЛИЧЕСТВО:ШАНС - кастомный предмет
 *
 * Если количество больше максимального стака, оно делится на стаки:
 * 128 камня = 2 стака по 64, 74 = 64 + 10.
 */
public class LootManager {

    private final JavaPlugin plugin;
    private final List<LootItem> lootItems = new ArrayList<>();
    private final List<LootItem> customLootItems = new ArrayList<>();
    // шанс по материалу - нужен, чтобы при нехватке слотов убирать самое обычное, а не редкое
    private final Map<Material, Double> materialChance = new HashMap<>();
    private final Random random = new Random();
    private final CustomItemsManager customItemsManager;

    // старые/ошибочные названия из конфига -> правильные
    private static final Map<String, String> ALIASES = Map.of(
            "SEEDS", "WHEAT_SEEDS"
    );

    public LootManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.customItemsManager = ((BossArena) plugin).getBossManager().getCustomItemsManager();
        loadGlobalLoot();
    }

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

    private void warnBad(String entry) {
        plugin.getLogger().warning("❌ Лут: неверный формат строки: " + entry);
    }

    /** "64-128" -> {64,128}; "1" -> {1,1}. */
    private int[] parseAmount(String s) {
        s = s.trim();
        int min, max;
        if (s.contains("-")) {
            String[] r = s.split("-");
            min = Integer.parseInt(r[0].trim());
            max = Integer.parseInt(r[1].trim());
        } else {
            min = max = Integer.parseInt(s);
        }
        if (min < 1) min = 1;
        if (max < min) max = min;
        return new int[]{min, max};
    }

    public List<ItemStack> generateLoot() {
        List<ItemStack> rewards = new ArrayList<>();

        // Предметы с шансом 100% - "гарантированные". Их несколько, и чтобы сундуки не были
        // одинаковыми, каждый раз выбирается случайная часть (chest.guaranteed_min/max).
        // Если guaranteed_max не задан или <= 0, выпадают все, как раньше.
        List<LootItem> guaranteed = new ArrayList<>();
        for (LootItem item : lootItems) {
            if (item.chance >= 100.0) { guaranteed.add(item); continue; }
            if (random.nextDouble() * 100 <= item.chance) {
                int amount = item.minAmount + random.nextInt(item.maxAmount - item.minAmount + 1);
                addSplit(rewards, item.material, amount);
            }
        }

        int gMax = plugin.getConfig().getInt("chest.guaranteed_max", 0);
        int gMin = plugin.getConfig().getInt("chest.guaranteed_min", 1);
        int take = guaranteed.size();
        if (gMax > 0 && guaranteed.size() > 0) {
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
            if (random.nextDouble() * 100 <= item.chance) droppedCustom.add(item);
        }

        if (!droppedCustom.isEmpty()) {
            LootItem selected = droppedCustom.get(random.nextInt(droppedCustom.size()));
            ItemStack customStack = customItemsManager.createItem(selected.itemId);
            if (customStack != null) {
                customStack.setAmount(1);
                rewards.add(customStack);
            }
        }

        return rewards;
    }

    /** Делит количество на стаки по максимальному размеру стака материала: 74 -> 64 + 10. */
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

        // Если стаков больше, чем слотов, убираем самое обычное:
        // кастомные предметы и редкие остаются, лишними становятся блоки с шансом 100%
        List<ItemStack> toPlace = new ArrayList<>(items);
        Collections.shuffle(toPlace, random);
        toPlace.sort(Comparator.comparingDouble(this::rarityOf));
        if (toPlace.size() > slotsToUse) toPlace = new ArrayList<>(toPlace.subList(0, slotsToUse));

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < chest.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, random);

        for (int i = 0; i < toPlace.size() && i < slots.size(); i++) {
            chest.setItem(slots.get(i), toPlace.get(i));
        }
    }

    /** Чем меньше число, тем реже предмет (кастомные всегда в приоритете). */
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

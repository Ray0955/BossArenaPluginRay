package com.example.bossarena;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class BossRotationManager {

    private final JavaPlugin plugin;
    private final List<BossData> bosses = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isActive = false;

    public BossRotationManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadBossesFromConfig();
    }

    public void loadBossesFromConfig() {
        bosses.clear();
        ConfigurationSection bossesSection = plugin.getConfig().getConfigurationSection("bosses");
        if (bossesSection == null) return;

        for (String key : bossesSection.getKeys(false)) {
            try {
                ConfigurationSection bossConfig = bossesSection.getConfigurationSection(key);
                if (!bossConfig.getBoolean("enabled", true)) continue;

                ConfigurationSection spawnSection = bossConfig.getConfigurationSection("spawn");
                BossSpawnPoint spawnPoint = null;
                if (spawnSection != null) {
                    String worldName = spawnSection.getString("world", "world");
                    World world = Bukkit.getWorld(worldName);
                    if (world != null) {
                        spawnPoint = new BossSpawnPoint(new Location(world,
                                spawnSection.getDouble("x", 0),
                                spawnSection.getDouble("y", 64),
                                spawnSection.getDouble("z", 0)),
                                spawnSection.getInt("radius", 10));
                    }
                }

                ConfigurationSection bossSection = bossConfig.getConfigurationSection("boss");
                EntityType bossType = EntityType.valueOf(bossSection.getString("type", "ZOMBIE").toUpperCase());
                double bossHealth = bossSection.getDouble("health", 200.0);
                String bossName = ChatColor.translateAlternateColorCodes('&', bossSection.getString("name", "Босс"));
                double bossDamage = bossSection.getDouble("damage", 5.0);

                ConfigurationSection helpersSection = bossConfig.getConfigurationSection("helpers");
                boolean helpersEnabled = helpersSection.getBoolean("enabled", true);
                String spawnMode = helpersSection.getString("spawn_mode", "ON_HEALTH_PERCENT");
                List<Double> thresholds = new ArrayList<>();
                for (String t : helpersSection.getStringList("health_thresholds")) {
                    try { thresholds.add(Double.parseDouble(t)); } catch (Exception ignored) {}
                }
                int helperCount = helpersSection.getInt("count", 3);
                double helperHealth = helpersSection.getDouble("health", 40.0);
                EntityType helperType = EntityType.valueOf(helpersSection.getString("type", "ZOMBIE").toUpperCase());
                boolean bossInvulnerable = helpersSection.getBoolean("boss_invulnerable", true);
                double helperDamage = helpersSection.getDouble("damage", 3.0);
                String spawnMessage = helpersSection.getString("spawn_message", "&6&l⚔️ Помощники появились!");
                int respawnDelay = bossConfig.getInt("respawn_delay", 30);

                bosses.add(new BossData(key, spawnPoint, bossType, bossHealth, bossName, bossDamage,
                        helpersEnabled, spawnMode, thresholds, helperCount, helperHealth, helperType,
                        bossInvulnerable, helperDamage, spawnMessage, respawnDelay));
            } catch (Exception e) {
                plugin.getLogger().warning("❌ Ошибка загрузки босса " + key + ": " + e.getMessage());
            }
        }

        if (plugin.getConfig().getBoolean("global.random_order", false)) Collections.shuffle(bosses);
    }

    public BossData getNextBoss() {
        if (bosses.isEmpty()) return null;
        int attempts = 0;
        while (attempts < bosses.size()) {
            int nextIndex = currentIndex + 1;
            if (nextIndex >= bosses.size()) {
                if (plugin.getConfig().getBoolean("global.infinite_rotation", true)) {
                    nextIndex = 0;
                    if (plugin.getConfig().getBoolean("global.random_order", false)) Collections.shuffle(bosses);
                } else return null;
            }
            currentIndex = nextIndex;
            BossData boss = bosses.get(nextIndex);
            if (boss.spawnPoint == null || boss.spawnPoint.location.getWorld() == null) { attempts++; continue; }
            isActive = true;
            return boss;
        }
        return null;
    }

    public BossData getCurrentBoss() {
        if (currentIndex >= 0 && currentIndex < bosses.size()) return bosses.get(currentIndex);
        return null;
    }

    public void reset() { currentIndex = -1; isActive = false; }
    public boolean isActive() { return isActive; }
    public List<BossData> getBosses() { return bosses; }
    public int getTotalBosses() { return bosses.size(); }
    public int getCurrentBossIndex() { return currentIndex; }

    public static class BossSpawnPoint {
        public final Location location;
        public final int radius;
        public BossSpawnPoint(Location location, int radius) {
            this.location = location;
            this.radius = radius;
        }
    }

    public static class BossData {
        public final String id;
        public final BossSpawnPoint spawnPoint;
        public final EntityType bossType;
        public final double bossHealth;
        public final String bossName;
        public final double bossDamage;
        public final boolean helpersEnabled;
        public final String spawnMode;
        public final List<Double> healthThresholds;
        public final int helperCount;
        public final double helperHealth;
        public final EntityType helperType;
        public final boolean bossInvulnerable;
        public final double helperDamage;
        public final String spawnMessage;
        public final int respawnDelay;

        public BossData(String id, BossSpawnPoint spawnPoint, EntityType bossType, double bossHealth,
                        String bossName, double bossDamage, boolean helpersEnabled, String spawnMode,
                        List<Double> healthThresholds, int helperCount, double helperHealth,
                        EntityType helperType, boolean bossInvulnerable, double helperDamage,
                        String spawnMessage, int respawnDelay) {
            this.id = id;
            this.spawnPoint = spawnPoint;
            this.bossType = bossType;
            this.bossHealth = bossHealth;
            this.bossName = bossName;
            this.bossDamage = bossDamage;
            this.helpersEnabled = helpersEnabled;
            this.spawnMode = spawnMode;
            this.healthThresholds = healthThresholds;
            this.helperCount = helperCount;
            this.helperHealth = helperHealth;
            this.helperType = helperType;
            this.bossInvulnerable = bossInvulnerable;
            this.helperDamage = helperDamage;
            this.spawnMessage = spawnMessage;
            this.respawnDelay = respawnDelay;
        }
    }
}
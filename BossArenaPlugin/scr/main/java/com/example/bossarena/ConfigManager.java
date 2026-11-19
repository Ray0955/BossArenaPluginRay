package com.example.bossarena;

import org.bukkit.plugin.java.JavaPlugin;

public class ConfigManager {

    private final JavaPlugin plugin;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isDonateEnabled() {
        return plugin.getConfig().getBoolean("donate.enabled", false);
    }

    public int getDonateRewardForPlace(int place) {
        return plugin.getConfig().getInt("donate.rewards.place_" + place, 10);
    }

    public double getMinDamagePercent() {
        return plugin.getConfig().getDouble("donate.min_damage_percent", 1.0);
    }

    public int getMinHits() {
        return plugin.getConfig().getInt("donate.min_hits", 1);
    }
}
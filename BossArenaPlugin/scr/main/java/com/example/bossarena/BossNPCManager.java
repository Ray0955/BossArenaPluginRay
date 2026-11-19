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

    public void loadNPCFromConfig() {
        spawnNPC();
        if (task == null) startTask();
    }

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

    public void removeNPC() {
        removeEntities();
        plugin.getConfig().set("boss_npc.location.placed", false);
        plugin.saveConfig();
    }

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
            v.setPersistent(false);
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

    private void startTask() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickNPC, 40L, 5L);
    }

    private void tickNPC() {
        if (!plugin.getConfig().getBoolean("boss_npc.enabled", true)) return;
        Location loc = getNPCLocation();
        if (loc == null) return;
        World w = loc.getWorld();
        if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;

        if (npc == null || !npc.isValid() || hologram == null || !hologram.isValid()) spawnNPC();
        if (npc == null) return;

        Collection<Player> near = w.getNearbyPlayers(loc, 24);
        if (near.isEmpty()) return;

        Player closest = null;
        double best = 36;
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

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!isNpcEntity(event.getRightClicked())) return;
        event.setCancelled(true);
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
        event.setCancelled(true);
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
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.
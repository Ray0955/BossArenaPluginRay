package com.example.bossarena;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

public class EventListener implements Listener {

    private final BossArena plugin;
    private final BossManager manager;
    private final CustomItemsManager customItemsManager;

    public EventListener(BossArena plugin) {
        this.plugin = plugin;
        this.manager = plugin.getBossManager();
        this.customItemsManager = plugin.getBossManager().getCustomItemsManager();
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity().getScoreboardTags().contains("boss_entity")) {
            if (manager.getBoss() != null && manager.getBoss().isInvulnerable()) {
                event.setCancelled(true);
                if (event.getDamager() instanceof Player p) p.sendMessage(ChatColor.RED + "❌ Босс неуязвим! Убейте помощников!");
                return;
            }
            if (event.getDamager() instanceof Player player) manager.registerDamage(player, event.getDamage());
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getScoreboardTags().contains("boss_entity")) manager.onBossDeath();
        if (event.getEntity().getScoreboardTags().contains("helper_entity")) {
            boolean allDead = manager.getHelpers().stream().allMatch(h -> h.isDead());
            if (allDead && manager.getBoss() != null && !manager.getBoss().isDead()) {
                manager.getBoss().setInvulnerable(false);
                manager.getBoss().setAI(true);
                Bukkit.broadcast(net.kyori.adventure.text.Component.text(ChatColor.translateAlternateColorCodes('&', "&a✅ Помощники повержены! Босс уязвим!")));
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            customItemsManager.applyAllBuffs(player);
            manager.addPlayerToBossBar(player);
        }, 5L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) { customItemsManager.clearPlayerBuffs(event.getPlayer()); }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        customItemsManager.clearPlayerBuffs(player);
        Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 5L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 5L);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 1L);
    }

    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 1L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 5L);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.CHEST && block.getType() != Material.TRAPPED_CHEST) return;
        Location chestLoc = block.getLocation().add(0.5, 0.5, 0.5);
        Location bossChestLoc = manager.getChestLocation();
        if (bossChestLoc == null) return;
        if (!chestLoc.getWorld().equals(bossChestLoc.getWorld())) return;
        if (chestLoc.distance(bossChestLoc) > 1.5) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getLocation().distance(chestLoc) > 5) { player.sendMessage(ChatColor.RED + "❌ Подойдите ближе!"); return; }
        if (!manager.isChestAccessible()) { player.sendMessage(ChatColor.RED + "❌ Сундук ещё не открыт!"); return; }
        if (!manager.isChestActive()) { player.sendMessage(ChatColor.RED + "❌ Сундук уже исчез!"); return; }
        if (manager.isChestEmpty()) { player.sendMessage(ChatColor.RED + "❌ Сундук пуст!"); return; }
        Inventory chest = manager.getChestInventory();
        if (chest == null) return;
        manager.restoreChestState();
        manager.addChestOpener(player);
        player.openInventory(chest);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv == null) return;
        Inventory chestInv = manager.getChestInventory();
        if (chestInv == null || !clickedInv.equals(chestInv)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 1L);
            return;
        }
        if (event.getAction().toString().contains("PLACE") || event.getAction().name().contains("DROP")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "❌ Нельзя класть или выбрасывать предметы!");
            return;
        }
        ItemStack currentItem = event.getCurrentItem();
        if (currentItem == null || currentItem.getType().isAir()) return;
        if (manager.isChestEmpty()) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "❌ Сундук пуст!");
            player.closeInventory();
            return;
        }
        manager.announceRareOnTake(player, currentItem.clone());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            manager.saveChestState();
            if (manager.isChestEmpty()) { manager.despawnChestWithEffects(false); player.closeInventory(); }
        }, 1L);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory chestInv = manager.getChestInventory();
        if (chestInv != null && event.getInventory().equals(chestInv)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "❌ Нельзя перемещать предметы!");
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 1L);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Inventory closedInv = event.getInventory();
        Inventory chestInv = manager.getChestInventory();
        if (chestInv != null && closedInv.equals(chestInv)) { manager.saveChestState(); return; }
        Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 1L);
    }

    private boolean isBossChestBlock(Block b) {
        if (!manager.isChestActive()) return false;
        Location l = manager.getChestLocation();
        if (l == null || l.getWorld() == null) return false;
        return b.getWorld().equals(l.getWorld()) && b.getX() == l.getBlockX() && b.getY() == l.getBlockY() && b.getZ() == l.getBlockZ();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBossChestBreak(BlockBreakEvent event) { if (isBossChestBlock(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityExplode(EntityExplodeEvent event) { event.blockList().removeIf(this::isBossChestBlock); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) { event.blockList().removeIf(this::isBossChestBlock); }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> customItemsManager.applyAllBuffs(player), 1L);
    }
}
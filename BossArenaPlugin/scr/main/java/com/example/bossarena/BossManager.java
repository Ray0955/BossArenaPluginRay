package com.example.bossarena;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class BossArena extends JavaPlugin {

    private static BossArena instance;
    private Economy economy;
    private BossManager bossManager;
    private CalendarManager calendarManager;
    private CalendarAdminGUI calendarAdminGUI;
    private BossNPCManager bossNPCManager;
    private ArenaManager arenaManager;
    private BagManager bagManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadConfig();

        if (!setupEconomy()) {
            getLogger().warning("❌ Vault не найден! Донат-валюта не будет работать!");
        }

        this.bossManager = new BossManager(this);
        this.calendarManager = new CalendarManager(this, bossManager);
        this.calendarAdminGUI = new CalendarAdminGUI(this, calendarManager);
        this.bossNPCManager = new BossNPCManager(this, bossManager);
        this.arenaManager = new ArenaManager(this, bossManager);

        this.bagManager = new BagManager(this, bossManager.getCustomItemsManager());
        bossManager.getCustomItemsManager().setBagManager(this.bagManager);

        getServer().getPluginManager().registerEvents(new EventListener(this), this);
        getServer().getPluginManager().registerEvents(calendarManager, this);
        getServer().getPluginManager().registerEvents(calendarAdminGUI, this);
        getServer().getPluginManager().registerEvents(arenaManager, this);

        bossManager.loadBossSpawnPoint();
        registerCommands();

        getLogger().info("✅ BossArena Enabled! (by Ray095)");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        getLogger().info("✅ Экономика: " + economy.getName());
        return economy != null;
    }

    private String msg(String key, String def) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + key, def));
    }

    private void registerCommands() {
        getCommand("boss").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) {
                sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!");
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "❌ Только для игроков!");
                return true;
            }
            if (args.length >= 1) {
                String id = args[0].toLowerCase();
                BossRotationManager.BossData data = bossManager.findBossData(id);
                if (data == null) {
                    player.sendMessage(msg("summon_unknown_boss", "&c❌ Босс '{ID}' не найден! Список: /bosslist").replace("{ID}", id));
                    return true;
                }
                World required = (data.spawnPoint != null) ? data.spawnPoint.location.getWorld() : null;
                if (required == null) {
                    player.sendMessage(msg("summon_world_unavailable", "&c❌ Мир для этого босса не загружен или не настроен!"));
                    return true;
                }
                if (!bossManager.isSpawnWorldValid(data)) {
                    player.sendMessage(msg("summon_bad_point", "&c❌ Точка спавна этого босса стоит не в том мире! Встаньте в {ENV} и введите /setboss {ID}")
                            .replace("{ENV}", bossManager.describeEnvironment(bossManager.getRequiredEnvironment(data)))
                            .replace("{ID}", data.id));
                    return true;
                }
                if (!player.getWorld().equals(required)) {
                    String text = switch (required.getEnvironment()) {
                        case THE_END -> msg("summon_need_end", "&c❌ Для вызова дракона нужно быть в Эндер-мире!");
                        case NETHER -> msg("summon_need_nether", "&c❌ Для вызова этого босса нужно быть в Нижнем мире!");
                        default -> msg("summon_need_world", "&c❌ Для вызова этого босса нужно быть в мире {WORLD}!").replace("{WORLD}", required.getName());
                    };
                    player.sendMessage(text);
                    return true;
                }
                if (bossManager.isBossAlive()) { player.sendMessage(ChatColor.RED + "❌ Босс уже жив!"); return true; }
                if (bossManager.isChestActive()) { player.sendMessage(ChatColor.RED + "❌ Сундук еще активен!"); return true; }
                if (bossManager.isSpawning()) { player.sendMessage(ChatColor.RED + "❌ Босс уже спавнится!"); return true; }
                if (bossManager.spawnBossById(id, false)) {
                    player.sendMessage(msg("summon_ok", "&a⚔️ Босс {BOSS} &aвызван!").replace("{BOSS}", data.bossName));
                } else {
                    player.sendMessage(msg("summon_world_unavailable", "&c❌ Не удалось вызвать босса: проверьте точку спавна в конфиге!"));
                }
                return true;
            }
            if (!bossManager.hasBossSpawnPoint()) {
                player.sendMessage(ChatColor.RED + "❌ Точка спавна не установлена!");
                player.sendMessage(ChatColor.YELLOW + "Используйте /setboss");
                return true;
            }
            if (bossManager.isBossAlive()) { player.sendMessage(ChatColor.RED + "❌ Босс уже жив!"); return true; }
            if (bossManager.isChestActive()) { player.sendMessage(ChatColor.RED + "❌ Сундук еще активен!"); return true; }
            if (bossManager.isSpawning()) { player.sendMessage(ChatColor.RED + "❌ Босс уже спавнится!"); return true; }
            bossManager.startArena();
            player.sendMessage(ChatColor.GREEN + "⚔️ Арена активирована!");
            return true;
        });

        getCommand("boss").setTabCompleter((sender, command, alias, args) -> {
            List<String> result = new ArrayList<>();
            if (!sender.hasPermission("bossarena.admin") || args.length != 1) return result;
            String prefix = args[0].toLowerCase();
            for (BossRotationManager.BossData b : bossManager.getRotationManager().getBosses()) {
                if (b.id.toLowerCase().startsWith(prefix)) result.add(b.id);
            }
            return result;
        });

        getCommand("setboss").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) { sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!"); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "❌ Только для игроков!"); return true; }
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "❌ /setboss <id_босса>");
                for (BossRotationManager.BossData boss : bossManager.getRotationManager().getBosses()) player.sendMessage(ChatColor.YELLOW + "  - " + boss.id);
                return true;
            }
            String bossId = args[0].toLowerCase();
            BossRotationManager.BossData targetBoss = null;
            for (BossRotationManager.BossData boss : bossManager.getRotationManager().getBosses()) {
                if (boss.id.equals(bossId)) { targetBoss = boss; break; }
            }
            if (targetBoss == null) { player.sendMessage(ChatColor.RED + "❌ Босс '" + bossId + "' не найден!"); return true; }
            World.Environment required = bossManager.getRequiredEnvironment(targetBoss);
            if (required != null && player.getWorld().getEnvironment() != required) {
                String key = required == World.Environment.THE_END ? "setboss_need_end" : required == World.Environment.NETHER ? "setboss_need_nether" : "setboss_need_normal";
                String def = required == World.Environment.THE_END ? "&c❌ Точку дракона можно ставить только в Эндер-мире!" : required == World.Environment.NETHER ? "&c❌ Точку этого босса можно ставить только в Нижнем мире!" : "&c❌ Точку этого босса можно ставить только в обычном мире!";
                player.sendMessage(msg(key, def));
                return true;
            }
            Location loc = player.getLocation();
            String path = "bosses." + bossId + ".spawn";
            getConfig().set(path + ".world", loc.getWorld().getName());
            getConfig().set(path + ".x", loc.getX());
            getConfig().set(path + ".y", loc.getY());
            getConfig().set(path + ".z", loc.getZ());
            getConfig().set(path + ".radius", 10);
            saveConfig();
            bossManager.getRotationManager().loadBossesFromConfig();
            bossManager.loadBossSpawnPoint();
            player.sendMessage(ChatColor.GREEN + "✅ Точка спавна для " + targetBoss.bossName + " установлена!");
            return true;
        });

        getCommand("bosslist").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) { sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!"); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "❌ Только для игроков!"); return true; }
            player.sendMessage(ChatColor.GOLD + "=== Список боссов ===");
            int i = 1;
            for (BossRotationManager.BossData boss : bossManager.getRotationManager().getBosses()) {
                String status = bossManager.getRotationManager().getCurrentBoss() == boss ? ChatColor.GREEN + " (АКТИВЕН)" : "";
                player.sendMessage(ChatColor.YELLOW + "#" + i + ": " + ChatColor.WHITE + boss.bossName + ChatColor.GRAY + " [" + boss.id + "]" + status);
                i++;
            }
            return true;
        });

        getCommand("damage").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "❌ Только для игроков!"); return true; }
            if (bossManager.isBossAlive() && bossManager.getBoss() != null && !bossManager.getBoss().isDead()) {
                double damage = bossManager.getPlayerDamage(player);
                int hits = bossManager.getPlayerHits(player);
                double bossHealth = bossManager.getBoss().getMaxHealth();
                double percent = bossHealth > 0 ? (damage / bossHealth) * 100 : 0;
                double remaining = bossManager.getBoss().getHealth();
                player.sendMessage(ChatColor.GOLD + "=== ТЕКУЩИЙ БОСС ===");
                player.sendMessage(ChatColor.YELLOW + "Босс: " + ChatColor.WHITE + bossManager.getBoss().getCustomName());
                player.sendMessage(ChatColor.YELLOW + "Ваш урон: " + ChatColor.WHITE + String.format("%.1f", damage));
                player.sendMessage(ChatColor.YELLOW + "Процент: " + ChatColor.WHITE + String.format("%.1f", percent) + "%");
                player.sendMessage(ChatColor.YELLOW + "Ударов: " + ChatColor.WHITE + hits);
                player.sendMessage(ChatColor.YELLOW + "Осталось HP: " + ChatColor.RED + String.format("%.1f", remaining));
                return true;
            }
            if (!bossManager.getLastBossName().isEmpty() && bossManager.getLastBossMaxHealth() > 0) {
                double damage = bossManager.getLastBossDamage(player);
                int hits = bossManager.getLastBossHits(player);
                double percent = bossManager.getLastBossMaxHealth() > 0 ? (damage / bossManager.getLastBossMaxHealth()) * 100 : 0;
                player.sendMessage(ChatColor.GOLD + "=== ПОСЛЕДНИЙ БОСС ===");
                player.sendMessage(ChatColor.YELLOW + "Босс: " + ChatColor.WHITE + bossManager.getLastBossName());
                player.sendMessage(ChatColor.YELLOW + "Ваш урон: " + ChatColor.WHITE + String.format("%.1f", damage));
                player.sendMessage(ChatColor.YELLOW + "Процент: " + ChatColor.WHITE + String.format("%.1f", percent) + "%");
                player.sendMessage(ChatColor.YELLOW + "Ударов: " + ChatColor.WHITE + hits);
                player.sendMessage(ChatColor.YELLOW + "Статус: " + ChatColor.RED + "УБИТ");
                return true;
            }
            player.sendMessage(ChatColor.RED + "❌ Нет данных о боссах!");
            return true;
        });

        getCommand("givecustom").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) { sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!"); return true; }
            if (args.length < 2) { sender.sendMessage(ChatColor.RED + "❌ /givecustom <игрок> <id> [кол-во]"); return true; }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) { sender.sendMessage(ChatColor.RED + "❌ Игрок не найден!"); return true; }
            String itemId = args[1];
            int amount = 1;
            if (args.length >= 3) {
                try { amount = Integer.parseInt(args[2]); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "❌ Количество должно быть числом!"); return true; }
            }
            org.bukkit.inventory.ItemStack item = bossManager.getCustomItemsManager().createItem(itemId);
            if (item == null) { sender.sendMessage(ChatColor.RED + "❌ Предмет '" + itemId + "' не найден!"); return true; }
            item.setAmount(amount);
            target.getInventory().addItem(item);
            sender.sendMessage(ChatColor.GREEN + "✅ Выдано " + amount + " шт. '" + itemId + "' игроку " + target.getName());
            bossManager.getCustomItemsManager().applyAllBuffs(target);
            return true;
        });

        getCommand("customitems").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) { sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!"); return true; }
            sender.sendMessage(ChatColor.GOLD + "=== Список кастомных предметов ===");
            for (CustomItemsManager.CustomItem item : bossManager.getCustomItemsManager().getCustomItems().values()) sender.sendMessage(ChatColor.YELLOW + "📦 " + item.id + ": " + item.name);
            return true;
        });

        getCommand("reloadcustom").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) { sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!"); return true; }
            reloadConfig();
            bossManager.getCustomItemsManager().loadCustomItems();
            bossManager.getCustomItemsManager().refreshAllBuffs();
            sender.sendMessage(ChatColor.GREEN + "✅ Кастомные предметы перезагружены!");
            return true;
        });

        getCommand("givebag").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) { sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!"); return true; }
            if (args.length < 1) { sender.sendMessage(ChatColor.RED + "❌ /givebag <игрок> [кол-во]"); return true; }
            Player target = Bukkit.getPlayer(args[0]);
            if (target == null) { sender.sendMessage(ChatColor.RED + "❌ Игрок не найден!"); return true; }
            int amount = 1;
            if (args.length >= 2) {
                try { amount = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            org.bukkit.inventory.ItemStack bag = bagManager.createBag();
            if (bag == null) { sender.sendMessage(ChatColor.RED + "❌ Сумка не найдена в custom_items (добавь custom_bag)!"); return true; }
            bag.setAmount(amount);
            target.getInventory().addItem(bag);
            sender.sendMessage(ChatColor.GREEN + "✅ Выдано " + amount + " сумок игроку " + target.getName());
            return true;
        });

        getCommand("calendar").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "❌ Только для игроков!"); return true; }
            calendarManager.openCalendar(player);
            return true;
        });

        getCommand("calendaradmin").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) { sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!"); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "❌ Только для игроков!"); return true; }
            calendarAdminGUI.openMainMenu(player);
            return true;
        });

        getCommand("bossarena").setExecutor(arenaManager);
        getCommand("bossarena").setTabCompleter(arenaManager);

        getCommand("bossnpc").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("bossarena.admin")) { sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!"); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage(ChatColor.RED + "❌ Только для игроков!"); return true; }
            if (args.length < 1) {
                player.sendMessage(ChatColor.GOLD + "=== NPC-витрина ===");
                player.sendMessage(ChatColor.YELLOW + "/bossnpc set");
                player.sendMessage(ChatColor.YELLOW + "/bossnpc remove");
                player.sendMessage(ChatColor.YELLOW + "/bossnpc reload");
                player.sendMessage(ChatColor.YELLOW + "/bossnpc disable");
                player.sendMessage(ChatColor.YELLOW + "/bossnpc enable");
                player.sendMessage(ChatColor.YELLOW + "/bossnpc status");
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "set" -> bossNPCManager.setNPCLocation(player);
                case "remove" -> { bossNPCManager.removeNPC(); player.sendMessage(ChatColor.GREEN + "✅ NPC удалена!"); }
                case "reload" -> { reloadConfig(); bossNPCManager.loadNPCFromConfig(); player.sendMessage(ChatColor.GREEN + "✅ NPC перезагружена!"); }
                case "disable" -> { bossNPCManager.setAvailable(false); player.sendMessage(ChatColor.RED + "🔧 Витрина ОТКЛЮЧЕНА!"); }
                case "enable" -> { bossNPCManager.setAvailable(true); player.sendMessage(ChatColor.GREEN + "✅ Витрина ВКЛЮЧЕНА!"); }
                case "status" -> {
                    boolean available = bossNPCManager.isAvailable();
                    player.sendMessage(ChatColor.GOLD + "=== Статус ===");
                    player.sendMessage(available ? ChatColor.GREEN + "✅ ДОСТУПНА" : ChatColor.RED + "🔧 ОТКЛЮЧЕНА");
                }
                default -> player.sendMessage(ChatColor.RED + "❌ Неизвестная команда!");
            }
            return true;
        });
    }

    @Override
    public void onDisable() {
        if (bossManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                bossManager.getCustomItemsManager().clearPlayerBuffs(player);
            }
            bossManager.cleanup();
        }
        if (bossNPCManager != null) bossNPCManager.cleanup();
        if (arenaManager != null) arenaManager.shutdown();
        getLogger().info("❌ BossArena Disabled!");
    }

    public static BossArena getInstance() { return instance; }
    public Economy getEconomy() { return economy; }
    public BossManager getBossManager() { return bossManager; }
    public CalendarManager getCalendarManager() { return calendarManager; }
    public BossNPCManager getBossNPCManager() { return bossNPCManager; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public BagManager getBagManager() { return bagManager; }
}
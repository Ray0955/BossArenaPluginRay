package com.example.bossarena; 

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public class BossManager {

    private final JavaPlugin plugin;
    private final BossRotationManager rotationManager;
    private final CustomItemsManager customItemsManager;
    private LootManager currentLootManager;
    private ConfigManager configManager;

    private LivingEntity boss;
    private List<LivingEntity> helpers = new ArrayList<>();
    private BossBar bossBar;
    private double bossMaxHealth = 0;

    private Inventory chestInventory;
    private Location chestLocation;
    private Block chestBlock;
    private TextDisplay chestHologram;
    private BlockDisplay fallingChest;
    private BukkitTask chestFallTask;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private boolean isChestActive = false;
    private boolean isChestOpen = false;
    private boolean isChestAccessible = false;
    private BukkitTask chestTask;
    private final Set<UUID> playersWhoOpened = new HashSet<>();
    private final List<ItemStack> savedChestContents = new ArrayList<>();

    private BukkitTask nextBossTimerTask;

    private boolean isBossAlive = false;
    private boolean isFirstSpawn = true;
    private boolean isSpawning = false;
    private Location bossSpawnPoint = null;
    private int spawnRadius = 10;

    private BossRotationManager.BossData activeBossData = null;
    private boolean continueRotation = true;

    private final Map<UUID, Double> playerDamage = new HashMap<>();
    private final Map<UUID, Integer> playerHits = new HashMap<>();

    private String lastBossName = "";
    private double lastBossMaxHealth = 0;
    private final Map<UUID, Double> lastBossDamage = new HashMap<>();
    private final Map<UUID, Integer> lastBossHits = new HashMap<>();

    private final Random random = new Random();
    private final List<Double> usedThresholds = new ArrayList<>();

    public BossManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.rotationManager = new BossRotationManager(plugin);
        this.customItemsManager = new CustomItemsManager(plugin);
        this.configManager = new ConfigManager(plugin);
        loadBossSpawnPoint();
    }

    public void loadBossSpawnPoint() {
        if (plugin.getConfig().contains("boss_spawn.world")) {
            String worldName = plugin.getConfig().getString("boss_spawn.world");
            double x = plugin.getConfig().getDouble("boss_spawn.x");
            double y = plugin.getConfig().getDouble("boss_spawn.y");
            double z = plugin.getConfig().getDouble("boss_spawn.z");
            double yaw = plugin.getConfig().getDouble("boss_spawn.yaw");
            double pitch = plugin.getConfig().getDouble("boss_spawn.pitch");
            spawnRadius = plugin.getConfig().getInt("boss_spawn.spawn_radius", 10);
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                this.bossSpawnPoint = new Location(world, x, y, z, (float) yaw, (float) pitch);
                plugin.getLogger().info("📍 Точка спавна: " + worldName);
            } else this.bossSpawnPoint = null;
        }
    }

    public boolean hasBossSpawnPoint() { return bossSpawnPoint != null; }
    public Location getBossSpawnPoint() { return bossSpawnPoint != null ? bossSpawnPoint.clone() : null; }
    public int getSpawnRadius() { return spawnRadius; }
    public Location getSpawnLocation() { return bossSpawnPoint; }

    public void startArena() {
        if (bossSpawnPoint == null) {
            Bukkit.broadcast(Component.text(ChatColor.RED + "❌ Точка спавна не установлена!"));
            return;
        }
        rotationManager.reset();
        isFirstSpawn = true;
        isSpawning = false;
        spawnNextBoss();
    }

    public boolean spawnBossById(String bossId) { return spawnBossById(bossId, true); }

    public boolean spawnBossById(String bossId, boolean continueRotation) {
        if (isBossAlive) return false;
        BossRotationManager.BossData bossData = findBossData(bossId);
        if (bossData == null) return false;
        Location spawnLoc = getRandomSpawnLocation(bossData);
        if (spawnLoc == null) return false;
        playerDamage.clear();
        playerHits.clear();
        usedThresholds.clear();
        currentLootManager = new LootManager(plugin);
        this.continueRotation = continueRotation;
        spawnBoss(bossData, spawnLoc);
        isSpawning = false;
        return true;
    }

    public BossRotationManager.BossData findBossData(String bossId) {
        if (bossId == null) return null;
        for (BossRotationManager.BossData data : rotationManager.getBosses()) {
            if (data.id.equalsIgnoreCase(bossId)) return data;
        }
        return null;
    }

    public World.Environment getRequiredEnvironment(BossRotationManager.BossData data) {
        if (data == null) return null;
        String custom = plugin.getConfig().getString("bosses." + data.id + ".required_environment");
        if (custom != null) {
            if (custom.equalsIgnoreCase("ANY")) return null;
            try { return World.Environment.valueOf(custom.toUpperCase()); }
            catch (IllegalArgumentException e) { plugin.getLogger().warning("❌ Неверный required_environment у босса " + data.id + ": " + custom); }
        }
        if (data.bossType == EntityType.ENDER_DRAGON) return World.Environment.THE_END;
        if (data.bossType == EntityType.PIGLIN_BRUTE) return World.Environment.NETHER;
        if (data.bossType == EntityType.MAGMA_CUBE) return World.Environment.NETHER;
        return null;
    }

    public String describeEnvironment(World.Environment env) {
        if (env == null) return "";
        return switch (env) {
            case THE_END -> "Эндер-мире";
            case NETHER -> "Нижнем мире";
            default -> "обычном мире";
        };
    }

    public boolean isSpawnWorldValid(BossRotationManager.BossData data) {
        World.Environment required = getRequiredEnvironment(data);
        if (required == null) return true;
        if (data.spawnPoint == null || data.spawnPoint.location.getWorld() == null) return false;
        return data.spawnPoint.location.getWorld().getEnvironment() == required;
    }

    private Location getRandomSpawnLocation(BossRotationManager.BossData bossData) {
        if (bossData == null || bossData.spawnPoint == null) return null;
        if (!isSpawnWorldValid(bossData)) {
            plugin.getLogger().warning("❌ Босс " + bossData.id + " не заспавнен: точка спавна должна быть в " + describeEnvironment(getRequiredEnvironment(bossData)) + ". Исправь через /setboss " + bossData.id + " или в config.yml.");
            return null;
        }
        Location center = bossData.spawnPoint.location;
        int radius = bossData.spawnPoint.radius;
        World world = center.getWorld();
        if (world == null) return null;
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = random.nextDouble() * radius;
        Location loc = center.clone().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
        loc.setY(findSpawnY(world, loc.getBlockX(), loc.getBlockZ(), (int) Math.floor(center.getY())));
        return loc;
    }

    private int findSpawnY(World world, int x, int z, int hint) {
        for (int y = hint + 4; y >= hint - 6; y--) {
            if (world.getBlockAt(x, y - 1, z).getType().isSolid() && world.getBlockAt(x, y, z).isPassable() && world.getBlockAt(x, y + 1, z).isPassable()) return y;
        }
        return world.getHighestBlockYAt(x, z) + 1;
    }

    private void spawnNextBoss() {
        if (isBossAlive) return;
        if (bossSpawnPoint == null) return;
        BossRotationManager.BossData bossData = rotationManager.getNextBoss();
        if (bossData == null) { Bukkit.broadcast(Component.text(ChatColor.GOLD + "🏆 Все боссы побеждены!")); return; }
        playerDamage.clear();
        playerHits.clear();
        usedThresholds.clear();
        currentLootManager = new LootManager(plugin);
        Location spawnLoc = getRandomSpawnLocation(bossData);
        if (spawnLoc == null) return;
        this.continueRotation = true;
        spawnBoss(bossData, spawnLoc);
        isSpawning = false;
    }

    private void spawnBoss(BossRotationManager.BossData bossData, Location location) {
        cleanupBossOnly();
        isBossAlive = true;
        isSpawning = false;
        activeBossData = bossData;
        if (chestLocation != null && isChestActive) despawnChestWithEffects(false);
        World world = location.getWorld();
        if (world == null) return;
        boss = (LivingEntity) world.spawnEntity(location, bossData.bossType);
        boss.setCustomName(bossData.bossName);
        boss.setCustomNameVisible(true);
        boss.setMaxHealth(bossData.bossHealth);
        boss.setHealth(bossData.bossHealth);
        boss.addScoreboardTag("boss_entity");
        setEntityDamage(boss, bossData.bossDamage);
        bossMaxHealth = bossData.bossHealth;
        bossBar = Bukkit.createBossBar(bossData.bossName, BarColor.RED, BarStyle.SEGMENTED_10);
        bossBar.setProgress(1.0);
        // ★ Радиус видимости боссбара (200 блоков по умолчанию) ★
        double viewRange = plugin.getConfig().getDouble("settings.bossbar_view_range", 200.0);
        bossBar.setViewRange(viewRange);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(location.getWorld())
                    && player.getLocation().distanceSquared(location) <= viewRange * viewRange) {
                bossBar.addPlayer(player);
            }
        }

        if (bossData.helpersEnabled) {
            if (bossData.spawnMode.equalsIgnoreCase("ON_SPAWN")) spawnHelpers(bossData, location);
            else if (bossData.spawnMode.equalsIgnoreCase("ON_HEALTH_PERCENT")) {
                usedThresholds.clear();
                if (bossData.bossInvulnerable) { boss.setAI(false); boss.setInvulnerable(true); }
            }
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (boss == null || boss.isDead() || !isBossAlive) { this.cancel(); return; }
                bossBar.setProgress(boss.getHealth() / boss.getMaxHealth());

                // ★ Обновление видимости по радиусу ★
                double range = plugin.getConfig().getDouble("settings.bossbar_view_range", 200.0);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    boolean inRange = p.getWorld().equals(boss.getWorld())
                            && p.getLocation().distanceSquared(boss.getLocation()) <= range * range;
                    if (inRange && !bossBar.getPlayers().contains(p)) {
                        bossBar.addPlayer(p);
                    } else if (!inRange && bossBar.getPlayers().contains(p)) {
                        bossBar.removePlayer(p);
                    }
                }
                // -------------------------------------

                if (bossData.helpersEnabled && bossData.spawnMode.equalsIgnoreCase("ON_HEALTH_PERCENT")) {
                    double currentHealthPercent = (boss.getHealth() / boss.getMaxHealth()) * 100;
                    for (double threshold : bossData.healthThresholds) {
                        if (currentHealthPercent <= threshold && !usedThresholds.contains(threshold)) {
                            usedThresholds.add(threshold);
                            spawnHelpers(bossData, location);
                            break;
                        }
                    }
                    if (bossData.bossInvulnerable) {
                        boolean anyAlive = helpers.stream().anyMatch(h -> !h.isDead());
                        boss.setAI(!anyAlive);
                        boss.setInvulnerable(anyAlive);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);

        if (plugin.getConfig().getBoolean("settings.spawn_particles", true)) {
            world.spawnParticle(Particle.DRAGON_BREATH, location.clone().add(0, 1, 0), 100, 1, 1, 1, 0.5);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, location.clone().add(0, 2, 0), 50, 0.5, 0.5, 0.5, 0.1);
        }
        if (plugin.getConfig().getBoolean("settings.spawn_sounds", true)) {
            world.playSound(location, Sound.ENTITY_WITHER_SPAWN, 2, 1);
            world.playSound(location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2, 1);
        }
        if (plugin.getConfig().getBoolean("global.announce_next_boss", true)) {
            if (isFirstSpawn) { Bukkit.broadcast(Component.text(ChatColor.GOLD + "⚔️ Первый босс появился!")); isFirstSpawn = false; }
            Bukkit.broadcast(Component.text(ChatColor.GOLD + "👾 " + bossData.bossName));
        }
    }

    private void spawnHelpers(BossRotationManager.BossData bossData, Location center) {
        if (boss == null || boss.isDead() || !isBossAlive) return;
        int count = bossData.helperCount;
        for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            Location loc = new Location(center.getWorld(), center.getX() + 5 * Math.cos(angle), center.getY(), center.getZ() + 5 * Math.sin(angle));
            LivingEntity helper = (LivingEntity) center.getWorld().spawnEntity(loc, bossData.helperType);
            helper.setCustomName(ChatColor.YELLOW + "Помощник " + (i + 1));
            helper.setCustomNameVisible(true);
            helper.setMaxHealth(bossData.helperHealth);
            helper.setHealth(bossData.helperHealth);
            helper.addScoreboardTag("helper_entity");
            setEntityDamage(helper, bossData.helperDamage);
            helpers.add(helper);
        }
        if (bossData.spawnMessage != null && !bossData.spawnMessage.isEmpty()) Bukkit.broadcast(Component.text(ChatColor.translateAlternateColorCodes('&', bossData.spawnMessage)));
    }

    private void setEntityDamage(LivingEntity entity, double damage) {
        if (entity == null) return;
        var attr = entity.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attr == null) return;
        NamespacedKey key = new NamespacedKey(plugin, "boss_damage");
        attr.removeModifier(key);
        attr.addModifier(new AttributeModifier(key, damage, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
    }

    public void registerDamage(Player player, double damage) {
        if (!isBossAlive || boss == null || boss.isDead()) return;
        UUID id = player.getUniqueId();
        playerDamage.put(id, playerDamage.getOrDefault(id, 0.0) + damage);
        playerHits.put(id, playerHits.getOrDefault(id, 0) + 1);
    }

    private void showDamageStats() {
        if (playerDamage.isEmpty()) return;
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(playerDamage.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Bukkit.broadcast(Component.text(ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("messages.damage_stats_title", "&6&l📊 Топ урона:"))));
        int pos = 1;
        for (Map.Entry<UUID, Double> entry : sorted) {
            if (pos > 5) break;
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) continue;
            double percent = (entry.getValue() / bossMaxHealth) * 100;
            Bukkit.broadcast(Component.text(ChatColor.translateAlternateColorCodes('&', "&f#" + pos + " &e" + p.getName() + " &7- &c" + String.format("%.1f", percent) + "%")));
            pos++;
        }
    }

    public void onBossDeath() {
        isBossAlive = false;
        BossRotationManager.BossData currentBoss = activeBossData != null ? activeBossData : rotationManager.getCurrentBoss();
        lastBossName = currentBoss != null ? currentBoss.bossName : "Неизвестный босс";
        lastBossMaxHealth = bossMaxHealth;
        lastBossDamage.clear();
        lastBossDamage.putAll(playerDamage);
        lastBossHits.clear();
        lastBossHits.putAll(playerHits);
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        Bukkit.broadcast(Component.text(ChatColor.GREEN + "✅ Босс " + (currentBoss != null ? currentBoss.bossName : "") + " повержен!"));
        showDamageStats();
        if (configManager.isDonateEnabled()) giveDonateCurrencyToParticipants();
        chestLocation = boss.getLocation().clone().add(0, 1, 0);
        spawnChest(chestLocation);
        if (continueRotation) {
            int respawnDelay = currentBoss != null ? currentBoss.respawnDelay : 30;
            startNextBossTimer(respawnDelay);
        }
    }

    public void forceBossDespawn() {
        if (!isBossAlive) return;
        String message = plugin.getConfig().getString("messages.boss_force_despawn", "&c⚠️ Босс исчез!");
        Bukkit.broadcast(Component.text(ChatColor.translateAlternateColorCodes('&', message)));
        cleanupBossOnly();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
    }

    private void startNextBossTimer(int seconds) {
        if (nextBossTimerTask != null) { nextBossTimerTask.cancel(); nextBossTimerTask = null; }
        Bukkit.broadcast(Component.text(ChatColor.GOLD + "⏳ Следующий босс через " + ChatColor.RED + seconds + ChatColor.GOLD + " сек!"));
        nextBossTimerTask = new BukkitRunnable() {
            int timeLeft = seconds;
            @Override
            public void run() {
                if (isBossAlive) { this.cancel(); nextBossTimerTask = null; return; }
                if (timeLeft <= 0) { this.cancel(); nextBossTimerTask = null; spawnNextBoss(); return; }
                if (timeLeft % 10 == 0 || timeLeft <= 5) Bukkit.broadcast(Component.text(ChatColor.GOLD + "⏳ Осталось " + ChatColor.RED + timeLeft + ChatColor.GOLD + " сек!"));
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void giveDonateCurrencyToParticipants() {
        if (!configManager.isDonateEnabled()) return;
        double minDamagePercent = configManager.getMinDamagePercent();
        int minHits = configManager.getMinHits();
        net.milkbowl.vault.economy.Economy economy = BossArena.getInstance().getEconomy();
        if (economy == null) return;
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(playerDamage.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int pos = 1;
        for (Map.Entry<UUID, Double> entry : sorted) {
            if (pos > 5) break;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) { pos++; continue; }
            int hits = playerHits.getOrDefault(entry.getKey(), 0);
            double percent = (entry.getValue() / bossMaxHealth) * 100;
            if (percent >= minDamagePercent && hits >= minHits) {
                int reward = configManager.getDonateRewardForPlace(pos);
                economy.depositPlayer(player, reward);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a💰 Вы заняли &6&l#" + pos + " &aместо: &6&l" + reward + " &aвалюты!"));
            }
            pos++;
        }
    }

    private void spawnChest(Location loc) {
        if (isChestActive && chestLocation != null) despawnChestWithEffects(false);
        isChestActive = true;
        isChestOpen = false;
        isChestAccessible = false;
        playersWhoOpened.clear();
        savedChestContents.clear();
        World world = loc.getWorld();
        if (world == null) return;
        Location blockLoc = loc.clone();
        blockLoc.setY(Math.floor(blockLoc.getY()));
        Block block = blockLoc.getBlock();
        if (block.getType() != Material.AIR && block.getType() != Material.CHEST) { blockLoc = blockLoc.add(0, 1, 0); block = blockLoc.getBlock(); }
        final Location finalLoc = blockLoc.clone();
        final Block finalBlock = block;
        final Location endLoc = finalLoc.clone().add(0.5, 0, 0.5);
        chestLocation = endLoc.clone();
        final double height = 18.0;
        final int duration = 50;
        world.playSound(finalLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.8f);
        world.playSound(finalLoc, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.4f);

        fallingChest = world.spawn(new Location(world, finalLoc.getBlockX(), finalLoc.getY() + height, finalLoc.getBlockZ()), BlockDisplay.class, d -> {
            d.setBlock(Bukkit.createBlockData(Material.CHEST));
            d.setPersistent(false);
            d.setTeleportDuration(2);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.ORANGE);
        });

        chestFallTask = new BukkitRunnable() {
            int tick = 0;
            @Override
            public void run() {
                if (!isChestActive) { removeFalling(); this.cancel(); return; }
                if (tick >= duration) { this.cancel(); chestFallTask = null; landChest(world, finalBlock, endLoc); return; }
                double t = (double) tick / duration;
                double y = finalLoc.getY() + height * (1.0 - t * t);
                Location pos = new Location(world, finalLoc.getBlockX(), y, finalLoc.getBlockZ());
                if (fallingChest != null && fallingChest.isValid()) fallingChest.teleport(pos);
                Location center = pos.clone().add(0.5, 0.5, 0.5);
                world.spawnParticle(Particle.FLAME, center, 6, 0.25, 0.25, 0.25, 0.02);
                world.spawnParticle(Particle.END_ROD, center.clone().add(0, 1, 0), 3, 0.2, 0.4, 0.2, 0.01);
                world.spawnParticle(Particle.SMOKE, center.clone().add(0, 0.8, 0), 4, 0.2, 0.3, 0.2, 0.01);
                double r = 3.0 * (1.0 - t) + 0.8;
                Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 120, 20), 1.4f);
                for (int i = 0; i < 20; i++) {
                    double a = 2 * Math.PI * i / 20;
                    world.spawnParticle(Particle.DUST, endLoc.getX() + Math.cos(a) * r, finalLoc.getY() + 0.15, endLoc.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, dust);
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void removeFalling() {
        if (fallingChest != null) { fallingChest.remove(); fallingChest = null; }
    }

    private void landChest(World world, Block finalBlock, Location endLoc) {
        removeFalling();
        finalBlock.setType(Material.CHEST);
        chestBlock = finalBlock;
        chestLocation = endLoc.clone();
        world.strikeLightningEffect(endLoc);
        world.playSound(endLoc, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.8f);
        world.playSound(endLoc, Sound.BLOCK_ANVIL_LAND, 1.5f, 0.6f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, endLoc, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, endLoc.clone().add(0, 0.5, 0), 80, 1, 0.5, 1, 0.3);

        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t > 10) { this.cancel(); return; }
                double r = 0.6 + t * 0.55;
                for (int i = 0; i < 28; i++) {
                    double a = 2 * Math.PI * i / 28;
                    world.spawnParticle(Particle.CLOUD, endLoc.getX() + Math.cos(a) * r, endLoc.getY() + 0.1, endLoc.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0.02);
                }
                t++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        removeHologram();
        chestHologram = world.spawn(endLoc.clone().add(0, 1.9, 0), TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);
            td.setBackgroundColor(Color.fromARGB(110, 45, 45, 45));
            td.setShadowed(false);
            td.setSeeThrough(false);
            td.setPersistent(false);
            td.setViewRange(0.6f);
            td.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 0, 1), new Vector3f(1.25f, 1.25f, 1.25f), new AxisAngle4f(0, 0, 0, 1)));
            td.text(chestText(false, 0.0));
        });

        int chestSize = plugin.getConfig().getInt("chest.size", 54);
        chestInventory = Bukkit.createInventory(null, chestSize, ChatColor.GOLD + "⭐ Сундук босса ⭐");
        List<ItemStack> loot = currentLootManager.generateLoot();
        currentLootManager.fillChest(chestInventory, loot);
        saveChestState();
        Bukkit.broadcast(Component.text(ChatColor.GOLD + "📦 Сундук босса появился!"));
        startChestTimer(endLoc);
    }

    private void removeHologram() {
        if (chestHologram != null) { chestHologram.remove(); chestHologram = null; }
    }

    private Component chestText(boolean open, double progress) {
        String bar = progressBar(progress, open);
        String text = open ? "&f&lСундук &a&lоткрыт\n\n&7Забирай лут скорее!\n\n&7Сундук исчезнет через:\n" + bar : "&f&lСундук &c&lзакрыт\n\n&7Пока не спеши — скоро он\n&7будет готов!\n\n&7Прогресс открытия:\n" + bar;
        return LEGACY.deserialize(ChatColor.translateAlternateColorCodes('&', text));
    }

    private String progressBar(double progress, boolean open) {
        int segments = 14;
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, progress)) * segments);
        String fill = (open && progress < 0.25) ? "&c" : "&6";
        StringBuilder sb = new StringBuilder("&8[").append(fill);
        for (int i = 0; i < filled; i++) sb.append("|");
        sb.append("&7");
        for (int i = filled; i < segments; i++) sb.append("|");
        sb.append("&8]");
        return sb.toString();
    }

    private void updateHologram(boolean open, double progress) {
        if (chestHologram != null && chestHologram.isValid()) chestHologram.text(chestText(open, progress));
    }

    public void announceRareOnTake(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        double itemChance = 100.0;
        String itemName = "";
        if (customItemsManager.isCustomItem(item)) {
            String itemId = customItemsManager.getCustomItemId(item);
            if (itemId != null && currentLootManager != null) {
                for (LootManager.LootItem lootItem : currentLootManager.getLootItems()) {
                    if (lootItem.isCustom && lootItem.itemId != null && lootItem.itemId.equals(itemId)) { itemChance = lootItem.chance; break; }
                }
                for (LootManager.LootItem lootItem : currentLootManager.getCustomLootItems()) {
                    if (lootItem.itemId != null && lootItem.itemId.equals(itemId)) { itemChance = lootItem.chance; break; }
                }
                CustomItemsManager.CustomItem customItem = customItemsManager.getCustomItem(itemId);
                itemName = customItem != null ? customItem.name : itemId;
            }
        } else {
            if (currentLootManager != null) {
                for (LootManager.LootItem lootItem : currentLootManager.getLootItems()) {
                    if (!lootItem.isCustom && lootItem.material == item.getType()) { itemChance = lootItem.chance; break; }
                }
            }
            itemName = item.getType().name().toLowerCase().replace("_", " ");
        }
        if (itemChance < 1.0) {
            String message = plugin.getConfig().getString("messages.rare_item", "&6&l✨ &d&l{PLAYER} &fполучил редкий предмет: &e&l{ITEM}");
            message = message.replace("{PLAYER}", player.getName()).replace("{ITEM}", itemName).replace("{AMOUNT}", String.valueOf(item.getAmount()));
            Bukkit.broadcast(Component.text(ChatColor.translateAlternateColorCodes('&', message)));
        }
    }

    public void saveChestState() {
        savedChestContents.clear();
        if (chestInventory == null) return;
        for (ItemStack item : chestInventory.getContents()) savedChestContents.add(item != null && !item.getType().isAir() ? item.clone() : null);
    }

    public void restoreChestState() {
        if (chestInventory == null || savedChestContents.isEmpty()) return;
        chestInventory.clear();
        for (int i = 0; i < savedChestContents.size() && i < chestInventory.getSize(); i++) {
            ItemStack item = savedChestContents.get(i);
            if (item != null && !item.getType().isAir()) chestInventory.setItem(i, item.clone());
        }
    }

    private void startChestTimer(Location loc) {
        int openTicks = Math.max(1, plugin.getConfig().getInt("chest_settings.open_time", 10)) * 20;
        int despawnTicks = Math.max(1, plugin.getConfig().getInt("chest_settings.despawn_time", 30)) * 20;
        chestTask = new BukkitRunnable() {
            int elapsed = 0;
            boolean opened = false;
            double angle = 0;
            @Override
            public void run() {
                if (!isChestActive) { this.cancel(); return; }
                World w = loc.getWorld();
                if (w == null) { this.cancel(); return; }
                Location c = loc.clone().add(0, 0.5, 0);
                if (!opened) {
                    angle += 0.35;
                    Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 120, 20), 1.6f);
                    for (int i = 0; i < 14; i++) {
                        double a = angle + i * (2 * Math.PI / 14);
                        w.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * 1.15, c.getY() + 0.15 * Math.sin(angle * 2 + i), c.getZ() + Math.sin(a) * 1.15, 1, 0, 0, 0, 0, dust);
                    }
                    w.spawnParticle(Particle.END_ROD, c, 2, 0.9, 0.7, 0.9, 0.0);
                    if (elapsed % 6 == 0) w.spawnParticle(Particle.NOTE, c.clone().add(0, 1.0, 0), 1, 0.8, 0.3, 0.8, 1.0);
                    if (elapsed >= openTicks) {
                        isChestOpen = true;
                        isChestAccessible = true;
                        w.playSound(loc, Sound.BLOCK_CHEST_OPEN, 1, 1);
                        w.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                        w.spawnParticle(Particle.TOTEM_OF_UNDYING, c.clone().add(0, 0.5, 0), 40, 0.6, 0.5, 0.6, 0.25);
                        Bukkit.broadcast(Component.text(ChatColor.GOLD + "📦 Сундук открыт!"));
                        opened = true;
                        elapsed = 0;
                        updateHologram(true, 1.0);
                        return;
                    }
                    if (elapsed % 10 == 0) updateHologram(false, elapsed / (double) openTicks);
                } else {
                    w.spawnParticle(Particle.END_ROD, c.clone().add(0, 0.6, 0), 2, 0.5, 0.4, 0.5, 0.02);
                    if (elapsed >= despawnTicks) { despawnChestWithEffects(!isChestEmpty()); this.cancel(); return; }
                    if (elapsed % 10 == 0) updateHologram(true, 1.0 - elapsed / (double) despawnTicks);
                }
                elapsed += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    public void despawnChestWithEffects(boolean hadItems) {
        if (chestLocation == null || !isChestActive) return;
        if (chestFallTask != null) { chestFallTask.cancel(); chestFallTask = null; }
        removeFalling();
        removeHologram();
        if (chestBlock != null && chestBlock.getType() == Material.CHEST) chestBlock.setType(Material.AIR);
        chestBlock = null;
        World world = chestLocation.getWorld();
        if (world != null && plugin.getConfig().getBoolean("chest_settings.despawn_effects", true)) world.spawnParticle(Particle.DRAGON_BREATH, chestLocation.clone().add(0, 0.5, 0), 100, 1, 1, 1, 0.0f);
        Bukkit.broadcast(Component.text(ChatColor.LIGHT_PURPLE + "✨ Сундук исчез!"));
        if (chestInventory != null) { chestInventory.clear(); chestInventory = null; }
        isChestActive = false;
        isChestOpen = false;
        isChestAccessible = false;
        playersWhoOpened.clear();
        savedChestContents.clear();
        chestLocation = null;
        if (chestTask != null) { chestTask.cancel(); chestTask = null; }
    }

    public boolean isChestEmpty() {
        if (chestInventory == null) return true;
        for (ItemStack item : chestInventory.getContents()) if (item != null && !item.getType().isAir()) return false;
        return true;
    }

    public void addChestOpener(Player player) { playersWhoOpened.add(player.getUniqueId()); }

    public void addPlayerToBossBar(Player player) {
        if (bossBar == null || !isBossAlive || boss == null || boss.isDead()) return;
        double viewRange = plugin.getConfig().getDouble("settings.bossbar_view_range", 200.0);
        if (player.getWorld().equals(boss.getWorld())
                && player.getLocation().distanceSquared(boss.getLocation()) <= viewRange * viewRange) {
            bossBar.addPlayer(player);
        }
    }

    public void cleanupBossOnly() {
        if (boss != null && !boss.isDead()) boss.remove();
        helpers.forEach(h -> { if (!h.isDead()) h.remove(); });
        helpers.clear();
        if (bossBar != null) { bossBar.removeAll(); bossBar = null; }
        if (nextBossTimerTask != null) { nextBossTimerTask.cancel(); nextBossTimerTask = null; }
        isBossAlive = false;
    }

    public void cleanup() {
        cleanupBossOnly();
        if (chestFallTask != null) { chestFallTask.cancel(); chestFallTask = null; }
        removeFalling();
        removeHologram();
        if (chestBlock != null && chestBlock.getType() == Material.CHEST) chestBlock.setType(Material.AIR);
        chestBlock = null;
        if (chestTask != null) { chestTask.cancel(); chestTask = null; }
        if (chestInventory != null) { chestInventory.clear(); chestInventory = null; }
        chestLocation = null;
        isChestActive = false;
        isChestOpen = false;
        isChestAccessible = false;
        playersWhoOpened.clear();
        savedChestContents.clear();
        isSpawning = false;
        activeBossData = null;
        playerDamage.clear();
        playerHits.clear();
        usedThresholds.clear();
    }

    public BossRotationManager getRotationManager() { return rotationManager; }
    public LootManager getLootManager() { return currentLootManager; }
    public CustomItemsManager getCustomItemsManager() { return customItemsManager; }
    public LivingEntity getBoss() { return boss; }
    public List<LivingEntity> getHelpers() { return helpers; }
    public BossBar getBossBar() { return bossBar; }
    public double getPlayerDamage(Player player) { return playerDamage.getOrDefault(player.getUniqueId(), 0.0); }
    public int getPlayerHits(Player player) { return playerHits.getOrDefault(player.getUniqueId(), 0); }
    public boolean isBossAlive() { return isBossAlive; }
    public boolean isChestActive() { return isChestActive; }
    public boolean isChestOpen() { return isChestOpen; }
    public boolean isChestAccessible() { return isChestAccessible; }
    public boolean isSpawning() { return isSpawning; }
    public Location getChestLocation() { return chestLocation; }
    public Inventory getChestInventory() { return chestInventory; }
    public Set<UUID> getChestOpeners() { return playersWhoOpened; }
    public String getLastBossName() { return lastBossName; }
    public double getLastBossMaxHealth() { return lastBossMaxHealth; }
    public double getLastBossDamage(Player player) { return lastBossDamage.getOrDefault(player.getUniqueId(), 0.0); }
    public int getLastBossHits(Player player) { return lastBossHits.getOrDefault(player.getUniqueId(), 0); }
}
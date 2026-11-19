package com.example.bossarena;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Арены боссов: по одной на каждый мир (Ray095). Команды: /bossarena build|remove|info|tp|sync
 *
 *  - Обычный мир: «Кольцо времени» (белая цитадель-циферблат, над ареной гигантское разбитое кольцо);
 *  - Нижний мир: «Кальдера» (кратер вулкана с рёбрами гигантского скелета, лавопады, багровые грибы);
 *  - Энд: «Змей эндера» (тёмная арена, вокруг которой свернулся гигантский змей, парящие кристаллы).
 *
 * Боссы используют арену того мира, в котором они живут (по типу или required_environment):
 * зомби, скелет и паук - обычную; пиглин и Магма-куб - Нижнего мира; Дракон - Энда.
 *
 * Стройка идёт порциями по тикам, старые блоки запоминаются для отката (до перезапуска сервера),
 * арены защищены от ломания и взрывов (в том числе от разрушений драконом).
 */
public class ArenaManager implements Listener, CommandExecutor, TabCompleter {

    private enum Style {
        HALO("Кольцо времени"), CALDERA("Кальдера"), SERPENT("Змей эндера");
        final String title;
        Style(String title) { this.title = title; }
    }

    private static final String[] FACING_RU = {"север", "восток", "юг", "запад"};
    private static final String[] DEBRIS = {"QUARTZ_BLOCK", "PURPUR_BLOCK", "AMETHYST_BLOCK", "SMOOTH_QUARTZ"};

    private static final int MIN_RADIUS = 24;
    private static final int MAX_RADIUS = 44;

    private static final World.Environment[] ENVS = {World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END};

    private static World.Environment envOf(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> World.Environment.NETHER;
            case THE_END -> World.Environment.THE_END;
            default -> World.Environment.NORMAL;
        };
    }

    private static Style styleFor(World.Environment e) {
        return switch (e) {
            case NETHER -> Style.CALDERA;
            case THE_END -> Style.SERPENT;
            default -> Style.HALO;
        };
    }

    private static String envName(World.Environment e) {
        return switch (e) {
            case NETHER -> "Нижний мир";
            case THE_END -> "Энд";
            default -> "Обычный мир";
        };
    }

    /** Набор блоков для строений. */
    private static class Pal {
        String[] white, floor;
        String qb, seat, accent, lamp, glass, dio, rod, pillar, roofA, roofB, chiseled, slab, stairA, stairB, ame;
    }

    private static final Pal LIGHT = new Pal();
    private static final Pal DARK = new Pal();
    static {
        LIGHT.white = new String[]{"QUARTZ_BRICKS", "QUARTZ_BRICKS", "SMOOTH_QUARTZ", "QUARTZ_BLOCK", "WHITE_CONCRETE", "CALCITE"};
        LIGHT.floor = new String[]{"SMOOTH_QUARTZ", "SMOOTH_QUARTZ", "QUARTZ_BLOCK", "WHITE_CONCRETE", "CALCITE", "QUARTZ_BRICKS"};
        LIGHT.qb = "QUARTZ_BRICKS"; LIGHT.seat = "QUARTZ_BLOCK"; LIGHT.accent = "PURPUR_BLOCK"; LIGHT.lamp = "SEA_LANTERN";
        LIGHT.glass = "WHITE_STAINED_GLASS"; LIGHT.dio = "DIORITE_WALL"; LIGHT.rod = "END_ROD"; LIGHT.pillar = "QUARTZ_PILLAR";
        LIGHT.roofA = "PURPUR_BLOCK"; LIGHT.roofB = "PINK_WOOL"; LIGHT.chiseled = "CHISELED_QUARTZ_BLOCK"; LIGHT.slab = "QUARTZ_SLAB";
        LIGHT.stairA = "PURPUR_STAIRS"; LIGHT.stairB = "QUARTZ_STAIRS"; LIGHT.ame = "AMETHYST_BLOCK";

        DARK.white = new String[]{"POLISHED_BLACKSTONE_BRICKS", "POLISHED_BLACKSTONE_BRICKS", "BLACKSTONE", "DEEPSLATE_BRICKS", "DEEPSLATE_TILES", "CRACKED_POLISHED_BLACKSTONE_BRICKS"};
        DARK.floor = new String[]{"POLISHED_BLACKSTONE", "POLISHED_BLACKSTONE", "BLACKSTONE", "DEEPSLATE_TILES", "POLISHED_BLACKSTONE_BRICKS", "DEEPSLATE_BRICKS"};
        DARK.qb = "POLISHED_BLACKSTONE_BRICKS"; DARK.seat = "POLISHED_BLACKSTONE"; DARK.accent = "AMETHYST_BLOCK"; DARK.lamp = "SEA_LANTERN";
        DARK.glass = "PURPLE_STAINED_GLASS"; DARK.dio = "BLACKSTONE_WALL"; DARK.rod = "END_ROD"; DARK.pillar = "POLISHED_BASALT";
        DARK.roofA = "PURPLE_CONCRETE"; DARK.roofB = "PURPUR_BLOCK"; DARK.chiseled = "CHISELED_POLISHED_BLACKSTONE"; DARK.slab = "POLISHED_BLACKSTONE_BRICK_SLAB";
        DARK.stairA = "POLISHED_BLACKSTONE_BRICK_STAIRS"; DARK.stairB = "DEEPSLATE_BRICK_STAIRS"; DARK.ame = "AMETHYST_BLOCK";
    }

    private static class Arena {
        final World.Environment env;
        final Style style;
        final World world;
        final int cx, y0, cz, radius, facing;     // facing: 0 север, 1 восток, 2 юг, 3 запад (куда "смотрит" главная деталь арены)

        Arena(World.Environment env, World world, int cx, int y0, int cz, int radius, int facing) {
            this.env = env;
            this.style = styleFor(env);
            this.world = world;
            this.cx = cx;
            this.y0 = y0;
            this.cz = cz;
            this.radius = radius;
            this.facing = facing;
        }

        int[] rot(int lx, int lz) {
            int x = lx, z = lz;
            for (int i = 0; i < facing; i++) { int nx = -z; z = x; x = nx; }
            return new int[]{x, z};
        }

        double protectRadius() {
            return radius + switch (style) { case HALO -> 4; case CALDERA -> 10; case SERPENT -> 18; };
        }
    }

    private final JavaPlugin plugin;
    private final BossManager bossManager;
    private final Map<World.Environment, Arena> arenas = new EnumMap<>(World.Environment.class);
    private final Map<World.Environment, Map<Long, BlockData>> snapshots = new EnumMap<>(World.Environment.class);
    private final Set<World.Environment> busy = EnumSet.noneOf(World.Environment.class);
    private BukkitTask ambientTask;

    public ArenaManager(JavaPlugin plugin, BossManager bossManager) {
        this.plugin = plugin;
        this.bossManager = bossManager;
        loadArenas();
        startAmbient();
    }

    // ───────────────────────── Данные арен ─────────────────────────

    private void loadArenas() {
        arenas.clear();
        var c = plugin.getConfig();
        ConfigurationSection places = c.getConfigurationSection("arena.places");
        if (places != null) {
            for (World.Environment e : ENVS) {
                ConfigurationSection s = places.getConfigurationSection(e.name());
                if (s == null) continue;
                World w = Bukkit.getWorld(s.getString("world", ""));
                if (w == null) continue;
                arenas.put(e, new Arena(e, w, s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getInt("radius", 28),
                        Math.floorMod(s.getInt("facing", 0), 4)));
            }
        }
        // старый формат: одна арена обычного мира
        if (!arenas.containsKey(World.Environment.NORMAL) && c.getString("arena.place.world") != null) {
            World w = Bukkit.getWorld(c.getString("arena.place.world"));
            if (w != null) {
                arenas.put(World.Environment.NORMAL, new Arena(World.Environment.NORMAL, w, c.getInt("arena.place.x"),
                        c.getInt("arena.place.y"), c.getInt("arena.place.z"), c.getInt("arena.place.radius", 28),
                        Math.floorMod(c.getInt("arena.place.facing", 0), 4)));
            }
        }
    }

    private void saveArena(Arena a) {
        var c = plugin.getConfig();
        c.set("arenas", null);
        c.set("arena.place", null);
        String p = "arena.places." + a.env.name();
        c.set(p + ".world", a.world.getName());
        c.set(p + ".x", a.cx);
        c.set(p + ".y", a.y0);
        c.set(p + ".z", a.cz);
        c.set(p + ".radius", a.radius);
        c.set(p + ".facing", a.facing);
        plugin.saveConfig();
    }

    private void forgetArena(World.Environment e) {
        arenas.remove(e);
        plugin.getConfig().set("arena.places." + e.name(), null);
        plugin.getConfig().set("arena.place", null);
        plugin.saveConfig();
    }

    // ───────────────────────── Какие боссы используют арену ─────────────────────────

    private World.Environment bossEnv(BossRotationManager.BossData data) {
        World.Environment req = bossManager.getRequiredEnvironment(data);
        return req == null ? World.Environment.NORMAL : (req == World.Environment.CUSTOM ? World.Environment.NORMAL : req);
    }

    /** null = босс использует арену этого мира, иначе причина, почему нет. */
    private String refuseReason(BossRotationManager.BossData data, World.Environment env) {
        if (data.bossType == EntityType.MAGMA_CUBE && env == World.Environment.NORMAL) return "Магма-куб живёт в Нижнем мире";
        World.Environment be = bossEnv(data);
        if (be != env) return "босс живёт в: " + envName(be);
        if (!plugin.getConfig().getBoolean("bosses." + data.id + ".arena.enabled", true)) {
            return "отключено в конфиге (bosses." + data.id + ".arena.enabled)";
        }
        return null;
    }

    private List<BossRotationManager.BossData> arenaBosses(World.Environment env) {
        List<BossRotationManager.BossData> list = new ArrayList<>();
        for (BossRotationManager.BossData d : bossManager.getRotationManager().getBosses()) {
            if (refuseReason(d, env) == null) list.add(d);
        }
        return list;
    }

    /** Центр арены становится точкой спавна всех боссов этого мира. */
    private int applyBossSpawns(Arena a) {
        var c = plugin.getConfig();
        int count = 0;
        for (BossRotationManager.BossData d : arenaBosses(a.env)) {
            String sp = "bosses." + d.id + ".spawn";
            c.set(sp + ".world", a.world.getName());
            c.set(sp + ".x", a.cx + 0.5);
            c.set(sp + ".y", (double) (a.y0 + 1));
            c.set(sp + ".z", a.cz + 0.5);
            c.set(sp + ".radius", 6);
            count++;
        }
        plugin.saveConfig();
        bossManager.getRotationManager().loadBossesFromConfig();
        return count;
    }

    private String bossNames(World.Environment env) {
        StringBuilder sb = new StringBuilder();
        for (BossRotationManager.BossData d : arenaBosses(env)) {
            if (sb.length() > 0) sb.append(ChatColor.GRAY).append(", ");
            sb.append(d.bossName);
        }
        return sb.length() == 0 ? ChatColor.GRAY + "(подходящих боссов нет)" : sb.toString();
    }

    // ───────────────────────── Команды ─────────────────────────

    /** Мир из аргумента (normal / nether / end) или мир игрока. */
    private World.Environment envFromArg(CommandSender sender, String[] args, int idx) {
        if (args.length > idx) {
            switch (args[idx].toLowerCase()) {
                case "normal", "overworld", "world" -> { return World.Environment.NORMAL; }
                case "nether", "hell" -> { return World.Environment.NETHER; }
                case "end", "the_end" -> { return World.Environment.THE_END; }
                default -> { }
            }
        }
        return sender instanceof Player p ? envOf(p.getWorld()) : null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bossarena.admin")) {
            sender.sendMessage(ChatColor.RED + "❌ У вас нет прав!");
            return true;
        }
        if (args.length == 0) { help(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "build" -> cmdBuild(sender, args);
            case "remove" -> cmdRemove(sender, args);
            case "info", "list" -> cmdInfo(sender);
            case "tp" -> cmdTp(sender, args);
            case "sync" -> cmdSync(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== Арены боссов (по одной на мир) ===");
        s.sendMessage(ChatColor.YELLOW + "/bossarena build [радиус] confirm" + ChatColor.GRAY + " - построить арену мира, в котором вы стоите");
        s.sendMessage(ChatColor.GRAY + "   обычный мир: Кольцо времени, Нижний мир: Кальдера, Энд: Змей эндера");
        s.sendMessage(ChatColor.YELLOW + "/bossarena remove [normal|nether|end]" + ChatColor.GRAY + " - откатить арену (до перезапуска сервера)");
        s.sendMessage(ChatColor.YELLOW + "/bossarena info" + ChatColor.GRAY + " - все арены и их боссы");
        s.sendMessage(ChatColor.YELLOW + "/bossarena tp [normal|nether|end]" + ChatColor.GRAY + " - телепорт на арену");
        s.sendMessage(ChatColor.YELLOW + "/bossarena sync [normal|nether|end]" + ChatColor.GRAY + " - направить боссов на арену (после добавления новых)");
    }

    private static int facingFromYaw(float yaw) {
        float y = ((yaw % 360) + 360) % 360;       // 0 юг, 90 запад, 180 север, 270 восток
        if (y >= 135 && y < 225) return 0;          // север
        if (y >= 225 && y < 315) return 1;          // восток
        if (y >= 315 || y < 45) return 2;           // юг
        return 3;                                   // запад
    }

    private void cmdBuild(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "❌ Только для игроков!");
            return;
        }
        int radius = plugin.getConfig().getInt("arena.radius", 28);
        boolean confirm = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("confirm")) {
                confirm = true;
            } else {
                try {
                    radius = Integer.parseInt(args[i]);
                } catch (NumberFormatException e) {
                    p.sendMessage(ChatColor.RED + "❌ /bossarena build [радиус " + MIN_RADIUS + "-" + MAX_RADIUS + "] confirm");
                    return;
                }
            }
        }
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));

        World w = p.getWorld();
        World.Environment env = envOf(w);
        Style style = styleFor(env);
        if (arenas.containsKey(env)) {
            Arena ex = arenas.get(env);
            p.sendMessage(ChatColor.RED + "❌ Арена этого мира уже есть (" + ex.world.getName() + " " + ex.cx + " " + ex.y0 + " " + ex.cz
                    + "). Сначала уберите её: /bossarena remove");
            return;
        }
        if (busy.contains(env)) {
            p.sendMessage(ChatColor.RED + "❌ Арена сейчас строится или откатывается!");
            return;
        }
        Location l = p.getLocation();
        int y0 = l.getBlockY() - 1;
        if (env == World.Environment.NETHER) {
            if (y0 < 14 || y0 + 36 > 124) {
                p.sendMessage(ChatColor.RED + "❌ В Нижнем мире арене нужно ~36 блоков высоты под потолком (между y=14 и y=88). Вы на y=" + y0 + ".");
                return;
            }
        } else if (y0 - 15 < w.getMinHeight() || y0 + 48 > w.getMaxHeight()) {
            p.sendMessage(ChatColor.RED + "❌ Здесь слишком высоко или низко (нужно ~48 блоков свободной высоты). Выберите другое место.");
            return;
        }
        if (bossManager.isBossAlive() || bossManager.isChestActive()) {
            p.sendMessage(ChatColor.RED + "❌ Дождитесь конца боя и исчезновения сундука!");
            return;
        }
        int facing = facingFromYaw(l.getYaw());

        if (!confirm) {
            p.sendMessage(ChatColor.GOLD + "⚠ Арена «" + style.title + "» (" + envName(env) + ") радиусом " + radius + " будет построена здесь: "
                    + l.getBlockX() + " " + y0 + " " + l.getBlockZ());
            p.sendMessage(ChatColor.GRAY + switch (style) {
                case HALO -> "Над ареной зависнет гигантское разбитое кольцо-циферблат, до 45 блоков в высоту.";
                case CALDERA -> "Вокруг арены вырастет кратер до 20 блоков, над ней арки из костей. Расчищается круг радиусом " + (radius + 9) + ".";
                case SERPENT -> "Вокруг арены свернётся гигантский змей, голова на стороне: " + FACING_RU[facing] + ". Расчищается круг радиусом " + (radius + 17) + ".";
            });
            p.sendMessage(ChatColor.GRAY + "Боссы на арене: " + bossNames(env));
            p.sendMessage(ChatColor.YELLOW + "Всё в этой зоне будет заменено (сундуки, постройки, деревья).");
            p.sendMessage(ChatColor.GRAY + "Откат: /bossarena remove (работает до перезапуска сервера).");
            p.sendMessage(ChatColor.GREEN + "Подтвердить: /bossarena build " + radius + " confirm");
            return;
        }

        Arena a = new Arena(env, w, l.getBlockX(), y0, l.getBlockZ(), radius, facing);
        Map<Long, BlockData> plan = new Planner(a).generate();
        p.sendMessage(ChatColor.GREEN + "🏗 Строю арену «" + style.title + "» (" + plan.size() + " блоков)...");
        runBuild(p, a, plan);
    }

    private void cmdRemove(CommandSender sender, String[] args) {
        World.Environment env = envFromArg(sender, args, 1);
        if (env == null || !arenas.containsKey(env)) {
            sender.sendMessage(ChatColor.RED + "❌ Арены этого мира нет! (/bossarena remove [normal|nether|end])");
            return;
        }
        if (busy.contains(env)) {
            sender.sendMessage(ChatColor.RED + "❌ Арена сейчас строится или откатывается!");
            return;
        }
        Map<Long, BlockData> snap = snapshots.get(env);
        if (snap == null || snap.isEmpty()) {
            forgetArena(env);
            sender.sendMessage(ChatColor.YELLOW + "⚠ Откат блоков недоступен (сервер перезапускался). Снята только защита арены, постройка осталась.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "♻ Откатываю арену (" + snap.size() + " блоков)...");
        runRestore(sender, arenas.get(env), snap);
    }

    private void cmdInfo(CommandSender sender) {
        if (arenas.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Арен пока нет. Встаньте на ровное место в нужном мире и введите /bossarena build");
            return;
        }
        for (World.Environment e : ENVS) {
            Arena a = arenas.get(e);
            if (a == null) {
                sender.sendMessage(ChatColor.GRAY + envName(e) + ": арены нет");
                continue;
            }
            sender.sendMessage(ChatColor.GOLD + "=== " + envName(e) + ": " + a.style.title + " ===");
            sender.sendMessage(ChatColor.YELLOW + "Где: " + ChatColor.WHITE + a.world.getName() + " " + a.cx + " " + a.y0 + " " + a.cz
                    + ChatColor.GRAY + " (радиус " + a.radius + ", направление: " + FACING_RU[a.facing] + ")");
            sender.sendMessage(ChatColor.YELLOW + "Боссы: " + bossNames(e));
        }
        StringBuilder skipped = new StringBuilder();
        for (BossRotationManager.BossData d : bossManager.getRotationManager().getBosses()) {
            boolean any = false;
            for (World.Environment e : ENVS) if (refuseReason(d, e) == null) any = true;
            if (!any) skipped.append(ChatColor.GRAY).append("  - ").append(d.id).append(": арена отключена в конфиге\n");
        }
        if (skipped.length() > 0) sender.sendMessage(ChatColor.YELLOW + "Без арены:\n" + skipped.toString().stripTrailing());
    }

    private void cmdTp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "❌ Только для игроков!");
            return;
        }
        World.Environment env = envFromArg(sender, args, 1);
        Arena a = env == null ? null : arenas.get(env);
        if (a == null) {
            p.sendMessage(ChatColor.RED + "❌ Арены этого мира нет! (/bossarena tp [normal|nether|end])");
            return;
        }
        p.teleport(new Location(a.world, a.cx + 0.5, a.y0 + 2, a.cz + 0.5));
        p.sendMessage(ChatColor.GREEN + "✅ Вы на арене «" + a.style.title + "».");
    }

    private void cmdSync(CommandSender sender, String[] args) {
        World.Environment env = envFromArg(sender, args, 1);
        Arena a = env == null ? null : arenas.get(env);
        if (a == null) {
            sender.sendMessage(ChatColor.RED + "❌ Арены этого мира нет! (/bossarena sync [normal|nether|end])");
            return;
        }
        int n = applyBossSpawns(a);
        sender.sendMessage(ChatColor.GREEN + "✅ На арену «" + a.style.title + "» направлено боссов: " + n + " (" + bossNames(env) + ChatColor.GREEN + ")");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("bossarena.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("build", "remove", "info", "tp", "sync")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args[0].equalsIgnoreCase("build")) {
            if (args.length == 2) out.addAll(List.of("28", "34", "40"));
            out.add("confirm");
        } else if (args.length == 2 && List.of("remove", "tp", "sync").contains(args[0].toLowerCase())) {
            out.addAll(List.of("normal", "nether", "end"));
        }
        return out;
    }

    // ───────────────────────── Стройка и откат (порциями по тикам) ─────────────────────────

    private int perTick() {
        return Math.max(200, Math.min(20000, plugin.getConfig().getInt("arena.blocks_per_tick", 1500)));
    }

    private void runBuild(Player player, Arena a, Map<Long, BlockData> plan) {
        busy.add(a.env);
        Map<Long, BlockData> snap = new HashMap<>();
        snapshots.put(a.env, snap);
        Iterator<Map.Entry<Long, BlockData>> it = plan.entrySet().iterator();
        int total = plan.size();
        int perTick = perTick();
        UUID playerId = player.getUniqueId();

        new BukkitRunnable() {
            int done = 0;
            int ticks = 0;

            @Override
            public void run() {
                int n = 0;
                while (it.hasNext() && n < perTick) {
                    Map.Entry<Long, BlockData> e = it.next();
                    long key = e.getKey();
                    Block b = a.world.getBlockAt(unpackX(key), unpackY(key), unpackZ(key));
                    snap.putIfAbsent(key, b.getBlockData());
                    b.setBlockData(e.getValue(), false);
                    n++;
                    done++;
                }
                Player p = Bukkit.getPlayer(playerId);
                if (p != null && ticks % 5 == 0) {
                    p.sendActionBar(Component.text(ChatColor.GOLD + "🏗 Строю арену: " + (done * 100 / Math.max(1, total)) + "%"));
                }
                ticks++;
                if (!it.hasNext()) {
                    this.cancel();
                    finishBuild(playerId, a);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finishBuild(UUID playerId, Arena a) {
        arenas.put(a.env, a);
        saveArena(a);
        int bosses = applyBossSpawns(a);
        busy.remove(a.env);

        Location center = new Location(a.world, a.cx + 0.5, a.y0 + 2, a.cz + 0.5);
        a.world.playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 0.8f);
        a.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center.clone().add(0, 1, 0), 120, 2, 1, 2, 0.4);

        Player p = Bukkit.getPlayer(playerId);
        if (p != null) {
            p.sendMessage(ChatColor.GREEN + "✅ Арена «" + a.style.title + "» готова!");
            p.sendMessage(ChatColor.GRAY + "Боссов направлено на арену: " + bosses + " (" + bossNames(a.env) + ChatColor.GRAY + ")");
            p.sendMessage(ChatColor.GRAY + "Вызов конкретного босса: /boss <id>, а ротация и календарь работают как раньше.");
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    private void runRestore(CommandSender sender, Arena a, Map<Long, BlockData> snap) {
        busy.add(a.env);
        Iterator<Map.Entry<Long, BlockData>> it = snap.entrySet().iterator();
        int perTick = perTick();
        new BukkitRunnable() {
            @Override
            public void run() {
                int n = 0;
                while (it.hasNext() && n < perTick) {
                    Map.Entry<Long, BlockData> e = it.next();
                    long key = e.getKey();
                    a.world.getBlockAt(unpackX(key), unpackY(key), unpackZ(key)).setBlockData(e.getValue(), false);
                    n++;
                }
                if (!it.hasNext()) {
                    this.cancel();
                    snapshots.remove(a.env);
                    forgetArena(a.env);
                    busy.remove(a.env);
                    sender.sendMessage(ChatColor.GREEN + "✅ Арена откачена. Точки спавна боссов остались в центре бывшей арены: "
                            + "поменяйте их через /setboss <id>.");
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ───────────────────────── Атмосфера ─────────────────────────

    private void startAmbient() {
        ambientTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Arena a : arenas.values()) {
                Location center = new Location(a.world, a.cx + 0.5, a.y0 + 1, a.cz + 0.5);
                if (a.world.getNearbyPlayers(center, a.radius + 48).isEmpty()) continue;
                double sp = a.radius * 0.6;
                switch (a.style) {
                    case HALO -> {
                        a.world.spawnParticle(Particle.END_ROD, center.clone().add(0, 8, 0), 8, sp, 6, sp, 0.01);
                        a.world.spawnParticle(Particle.END_ROD, center.clone().add(0, 36, 0), 10, a.radius, 3, a.radius, 0.01);
                        a.world.spawnParticle(Particle.ENCHANT, center.clone().add(0, 24, 0), 14, 3, 3, 3, 0.4);
                    }
                    case CALDERA -> {
                        a.world.spawnParticle(Particle.ASH, center.clone().add(0, 6, 0), 30, a.radius, 8, a.radius, 0);
                        a.world.spawnParticle(Particle.LAVA, center.clone().add(0, 2, 0), 3, sp, 1, sp, 0);
                        a.world.spawnParticle(Particle.SMALL_FLAME, center.clone().add(0, 4, 0), 6, sp, 3, sp, 0.01);
                    }
                    case SERPENT -> {
                        a.world.spawnParticle(Particle.REVERSE_PORTAL, center.clone().add(0, 8, 0), 40, sp, 8, sp, 0.2);
                        a.world.spawnParticle(Particle.END_ROD, center.clone().add(0, 10, 0), 6, a.radius, 8, a.radius, 0.01);
                    }
                }
            }
        }, 40L, 10L);
    }

    // ───────────────────────── Защита арен ─────────────────────────

    private boolean inArena(Location l) {
        if (l == null || l.getWorld() == null) return false;
        for (Arena a : arenas.values()) {
            if (!a.world.equals(l.getWorld())) continue;
            int y = l.getBlockY();
            if (y < a.y0 - 14 || y > a.y0 + 70) continue;
            double dx = l.getBlockX() - a.cx, dz = l.getBlockZ() - a.cz;
            double r = a.protectRadius();
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        if (inArena(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("bossarena.arena.bypass")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        if (inArena(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("bossarena.arena.bypass")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (inArena(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("bossarena.arena.bypass")) e.setCancelled(true);
    }

    /** Заодно защищает арену от разрушений Драконом Края (он ломает блоки через это событие). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> inArena(b.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> inArena(b.getLocation()));
    }

    public void shutdown() {
        if (ambientTask != null) { ambientTask.cancel(); ambientTask = null; }
    }

    // ───────────────────────── Упаковка координат ─────────────────────────

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
    }
    private static int unpackX(long k) { return (int) (k >> 38); }
    private static int unpackZ(long k) { return (int) ((k << 26) >> 38); }
    private static int unpackY(long k) { return (int) (k & 0xFFF) - 2048; }

    // ═════════════════════════ Планировщик арены ═════════════════════════

    /**
     * Строит в памяти план "какой блок где", потом он ставится порциями.
     * Методы с "L" (putL, supportL) работают в локальных координатах: главная деталь арены
     * (голова змея и т.п.) всегда "на севере", потом всё поворачивается по взгляду игрока.
     */
    private class Planner {
        final Arena a;
        final World world;
        final int cx, y0, cz, R, F;       // F = радиус боевой площадки
        final Map<Long, BlockData> plan = new LinkedHashMap<>();
        final Map<String, BlockData> cache = new HashMap<>();
        final Set<Long> used = new HashSet<>();
        final Random rnd = new Random();
        final BlockData air = Material.AIR.createBlockData();
        final int wallH = 9;
        Pal pal;
        int clearH = 24;

        Planner(Arena arena) {
            this.a = arena;
            this.world = arena.world;
            this.cx = arena.cx;
            this.y0 = arena.y0;
            this.cz = arena.cz;
            this.R = arena.radius;
            this.F = arena.style == Style.CALDERA ? R - 8 : R - 9;
        }

        // ---- помощники ----

        BlockData blk(String name) {
            return cache.computeIfAbsent(name, n -> {
                Material m = Material.matchMaterial(n);
                if (m == null || !m.isBlock()) m = Material.QUARTZ_BLOCK;
                return m.createBlockData();
            });
        }

        BlockData dat(String full) {
            try {
                return Bukkit.createBlockData(full);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        void put(int x, int y, int z, BlockData d) { if (d != null) plan.put(pack(x, y, z), d); }
        void put(int x, int y, int z, String material) { put(x, y, z, blk(material)); }
        String pick(String[] arr) { return arr[rnd.nextInt(arr.length)]; }
        long col(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

        void putL(int lx, int ly, int lz, BlockData d) {
            int[] w = a.rot(lx, lz);
            put(cx + w[0], y0 + ly, cz + w[1], d);
        }
        void putL(int lx, int ly, int lz, String material) { putL(lx, ly, lz, blk(material)); }

        /** Подстилаем основание под постройку до твёрдой земли (не больше 14 блоков вниз). */
        void support(int x, int z, String mat) {
            for (int k = 0; k <= 14; k++) {
                if (k > 0 && world.getBlockAt(x, y0 - k, z).getType().isSolid()) break;
                put(x, y0 - k, z, mat);
            }
        }
        void supportL(int lx, int lz) {
            int[] w = a.rot(lx, lz);
            support(cx + w[0], cz + w[1], pal.qb);
        }

        double angle(double dx, double dz) {
            double v = Math.atan2(dz, dx);
            return v < 0 ? v + 2 * Math.PI : v;
        }

        boolean corridor(int dx, int dz) { return Math.abs(dx) <= 2 || Math.abs(dz) <= 2; }

        BlockData stairsOut(String material, int dx, int dz) {
            String facing = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");   // ступени растут наружу
            return dat("minecraft:" + material.toLowerCase() + "[facing=" + facing + ",half=bottom]");
        }

        int[] spot(double minD, double maxD) {
            for (int t = 0; t < 14; t++) {
                double d = minD + rnd.nextDouble() * (maxD - minD);
                double ang = rnd.nextDouble() * 2 * Math.PI;
                int x = cx + (int) Math.round(Math.cos(ang) * d);
                int z = cz + (int) Math.round(Math.sin(ang) * d);
                if (Math.abs(x - cx) <= 2 || Math.abs(z - cz) <= 2) continue;
                if (used.add(col(x, z))) return new int[]{x, z};
            }
            return null;
        }

        // ---- генерация ----

        Map<Long, BlockData> generate() {
            switch (a.style) {
                case HALO -> { pal = LIGHT; clearH = 24; citadel(); halo(); }
                case SERPENT -> { pal = DARK; clearH = 34; citadel(); serpent(); bigCrystals(); }
                case CALDERA -> { pal = DARK; caldera(); }
            }
            return plan;
        }

        // ═════════ Цитадель-циферблат (Кольцо времени и Змей эндера) ═════════

        void citadel() {
            groundAndFloor();
            barrier();
            tribunes();
            walls();
            pillars();
            lampPosts();
            groundProps();
            turrets();
        }

        boolean onHand(int dx, int dz, double ang, double len) {
            double along = dx * Math.cos(ang) + dz * Math.sin(ang);
            double perp = Math.abs(-dx * Math.sin(ang) + dz * Math.cos(ang));
            return along >= 3.8 && along <= len && perp < 0.6;
        }

        /** Пол в виде циферблата: светящиеся стрелки, часовые метки, цветные кольца. */
        String floorBlock(int dx, int dz, double d) {
            if (d <= 3.4) {                                           // помост босса
                if (d < 1.5) return "CRYING_OBSIDIAN";
                if (d < 2.6) return pal.accent;
                return pal.qb;
            }
            if (d >= 5.0 && d <= 5.9) return pal.ame;                 // кольцо призыва
            if (d > F) return corridor(dx, dz) ? pal.qb : pick(pal.white);
            if (onHand(dx, dz, Math.toRadians(-60), 12) || onHand(dx, dz, Math.toRadians(-120), 8)) return "SEA_LANTERN";   // стрелки
            double theta = angle(dx, dz);
            double quarter = Math.PI / 2, hour = Math.PI / 6;
            double devMajor = Math.abs(theta - Math.round(theta / quarter) * quarter) * d;
            double devMinor = Math.abs(theta - Math.round(theta / hour) * hour) * d;
            if (d >= F - 8 && d <= F - 2.6 && devMajor < 1.0) return pal.accent;    // 12, 3, 6, 9
            if (d >= F - 6 && d <= F - 2.6 && devMinor < 0.6) return pal.accent;    // остальные часы
            if (d >= F - 1.6 && d <= F - 0.6) return pal.accent;                    // внешняя кайма
            if (d > F - 0.6) return pal.qb;
            if ((d > 8.0 && d < 8.8) || (d > 10.0 && d < 10.5)) return pal.accent;
            return pick(pal.floor);
        }

        void groundAndFloor() {
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (d > R + 0.5) continue;
                    int x = cx + dx, z = cz + dz;

                    for (int y = y0 + clearH; y > y0; y--) put(x, y, z, air);
                    String fb = floorBlock(dx, dz, d);
                    put(x, y0, z, fb);
                    if (d <= 3.4) put(x, y0 + 1, z, fb);              // помост выше пола на блок
                    for (int k = 1; k <= 3; k++) put(x, y0 - k, z, "STONE_BRICKS");
                    for (int k = 4; k <= 14; k++) {
                        Block b = world.getBlockAt(x, y0 - k, z);
                        if (b.getType().isSolid()) break;
                        put(x, y0 - k, z, "STONE_BRICKS");
                    }
                }
            }
        }

        /** Низкая ограда между полем боя и трибунами (1.5 блока, не перепрыгнуть), проходы у ворот. */
        void barrier() {
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (d <= F || d > F + 1.0 || corridor(dx, dz)) continue;
                    put(cx + dx, y0 + 1, cz + dz, pal.dio);
                }
            }
        }

        /** Три яруса трибун: акцентный, основной, акцентный. */
        void tribunes() {
            String[] stairs = {pal.stairA, pal.stairB, pal.stairA};
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (d <= F + 1.0 || d > F + 5.8 || corridor(dx, dz)) continue;
                    int tier = Math.max(0, Math.min(2, (int) ((d - (F + 1.0)) / 1.6)));
                    int x = cx + dx, z = cz + dz;
                    for (int k = 1; k <= tier; k++) put(x, y0 + k, z, pal.seat);
                    put(x, y0 + tier + 1, z, stairsOut(stairs[tier], dx, dz));
                }
            }
        }

        void walls() {
            double slitStep = 2 * Math.PI / 32;
            List<Double> breaks = new ArrayList<>();                       // разрушенные участки стены
            for (int i = 0; i < 5; i++) {
                for (int t = 0; t < 10; t++) {
                    double ang = rnd.nextDouble() * 2 * Math.PI;
                    double toAxis = Math.abs(ang - Math.round(ang / (Math.PI / 2)) * (Math.PI / 2));
                    if (toAxis > 0.28) { breaks.add(ang); break; }          // не ломаем ворота
                }
            }
            for (int dx = -R; dx <= R; dx++) {
                for (int dz = -R; dz <= R; dz++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (d <= R - 1.5 || d > R + 0.5) continue;
                    int x = cx + dx, z = cz + dz;

                    boolean gate = (Math.abs(dx) <= 2 && Math.abs(dz) > R - 3) || (Math.abs(dz) <= 2 && Math.abs(dx) > R - 3);
                    double ang = angle(dx, dz);
                    double dev = Math.abs(ang - Math.round(ang / slitStep) * slitStep) * R;
                    boolean slit = !gate && dev < 0.7;                       // бойницы
                    int hh = wallH;
                    boolean broken = false;
                    for (double b : breaks) {
                        double diff = Math.abs(ang - b);
                        diff = Math.min(diff, 2 * Math.PI - diff);
                        if (diff * R < 3.2) { hh = wallH - 3 - (int) (rnd.nextDouble() * 4); broken = true; break; }
                    }
                    for (int y = y0 + 1; y <= y0 + hh; y++) {
                        int rel = y - y0;
                        if (gate && rel <= 5) continue;                       // проём ворот
                        if (slit && (rel == 5 || rel == 6)) continue;
                        String m;
                        if (rel == 4) m = pal.accent;                          // цветной пояс
                        else if (rel >= 2 && rnd.nextDouble() < 0.03) m = "SEA_LANTERN";
                        else if (rel >= 2 && rnd.nextDouble() < 0.05) m = pal.chiseled;
                        else m = pick(pal.white);
                        put(x, y, z, m);
                    }
                    if (gate) put(x, y0, z, pal.qb);
                    else if (!broken && Math.floorMod(dx + dz, 2) == 0) put(x, y0 + hh + 1, z, pick(pal.white));   // зубцы
                    if (broken && rnd.nextDouble() < 0.12) {                  // обломки у разрушенной стены
                        put(x, y0 + 1, z, pal.seat);
                        if (rnd.nextBoolean()) put(x + (dx > 0 ? -1 : 1), y0 + 1, z, pal.slab);
                    }
                }
            }
        }

        /** 8 высоких колонн у стены с огнями-маяками. */
        void pillars() {
            double pr = R - 2.5;
            for (int k = 0; k < 8; k++) {
                double ang = 2 * Math.PI * k / 8 + Math.PI / 8;
                int dx = (int) Math.round(Math.cos(ang) * pr), dz = (int) Math.round(Math.sin(ang) * pr);
                int px = cx + dx, pz = cz + dz;
                for (int ix = -1; ix <= 1; ix++) for (int iz = -1; iz <= 1; iz++) {
                    put(px + ix, y0 + 1, pz + iz, pal.seat);
                    put(px + ix, y0 + 13, pz + iz, pal.accent);
                }
                for (int y = y0 + 2; y <= y0 + 12; y++) put(px, y, pz, pal.pillar);
                put(px, y0 + 14, pz, pal.lamp);
                for (int y = y0 + 15; y <= y0 + 17; y++) put(px, y, pz, pal.rod);
                used.add(col(px, pz));
            }
        }

        void lampPosts() {
            double pr = F - 1.5;
            for (int k = 0; k < 12; k++) {
                double ang = 2 * Math.PI * k / 12 + Math.PI / 12;
                int dx = (int) Math.round(Math.cos(ang) * pr), dz = (int) Math.round(Math.sin(ang) * pr);
                int px = cx + dx, pz = cz + dz;
                for (int y = y0 + 1; y <= y0 + 3; y++) put(px, y, pz, pal.dio);
                put(px, y0 + 4, pz, pal.lamp);
                used.add(col(px, pz));
            }
        }

        /** Кристаллы аметиста и обломки колонн на поле. */
        void groundProps() {
            for (int i = 0; i < 12; i++) {
                int[] p = spot(11, F - 3);
                if (p == null) continue;
                put(p[0], y0, p[1], pal.ame);
                put(p[0], y0 + 1, p[1], dat("minecraft:amethyst_cluster[facing=up]"));
            }
            for (int i = 0; i < 6; i++) {
                int[] p = spot(9, F - 3);
                if (p == null) continue;
                int h = 1 + rnd.nextInt(3);
                for (int y = 1; y <= h; y++) put(p[0], y0 + y, p[1], pal.pillar);
                if (rnd.nextBoolean()) put(p[0] + 1, y0 + 1, p[1], pal.slab);
            }
        }

        /** Круглая башня с пилястрами, окнами, балконом и шпилем. rr - радиус, h - высота стен. */
        void tower(int tlx, int tlz, int rr, int h, int rods) {
            for (int ix = -rr - 2; ix <= rr + 2; ix++) {
                for (int iz = -rr - 2; iz <= rr + 2; iz++) {
                    double r = Math.hypot(ix, iz);
                    int lx = tlx + ix, lz = tlz + iz;
                    if (r <= rr + 0.5 && r > rr - 0.9) {
                        supportL(lx, lz);
                        double ang = Math.atan2(iz, ix);
                        boolean pil = Math.abs(ang - Math.round(ang / (Math.PI / 4)) * (Math.PI / 4)) < 0.3;
                        for (int y = 1; y <= h; y++) {
                            String m;
                            if (pil) m = pal.seat;
                            else if (y >= 3 && (y % 7 == 3 || y % 7 == 4)) m = pal.glass;
                            else if (y % 7 == 0) m = pal.accent;
                            else m = pick(pal.white);
                            putL(lx, y, lz, m);
                        }
                    }
                    if (r <= rr + 1.5 && r > rr + 0.5) {
                        for (int y = 7; y < h; y += 7) putL(lx, y, lz, pal.slab);
                        putL(lx, h + 1, lz, pal.seat);
                        if (Math.floorMod(ix + iz, 2) == 0) putL(lx, h + 2, lz, pal.slab);
                    }
                    if (r <= rr + 0.5) putL(lx, h + 1, lz, pal.seat);
                }
            }
            int roof = (int) Math.round(rr * 2.8);
            for (int i = 0; i <= roof; i++) {
                double ri = (rr + 0.7) * (1.0 - (double) i / (roof + 1));
                int y = h + 2 + i;
                for (int ix = -rr - 2; ix <= rr + 2; ix++) {
                    for (int iz = -rr - 2; iz <= rr + 2; iz++) {
                        double r = Math.hypot(ix, iz);
                        boolean fill = (i == roof) ? r <= 0.9 : (r <= ri + 0.3 && r > ri - 1.0);
                        if (fill) putL(tlx + ix, y, tlz + iz, i % 3 == 0 ? pal.roofA : pal.roofB);
                    }
                }
            }
            for (int j = 1; j <= rods; j++) putL(tlx, h + 2 + roof + j, tlz, pal.rod);
        }

        /** Тонкие башенки по бокам всех четырёх ворот. */
        void turrets() {
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] dir : dirs) {
                int px = -dir[1], pz = dir[0];
                for (int side : new int[]{-5, 5}) {
                    tower(dir[0] * (R - 1) + px * side, dir[1] * (R - 1) + pz * side, 2, 16, 4);
                }
            }
        }

        void crystal(int x, int y, int z, int n, String top, String bot, String core, String spire) {
            for (int dx = -n; dx <= n; dx++) {
                for (int dy = -n - 1; dy <= n + 1; dy++) {
                    for (int dz = -n; dz <= n; dz++) {
                        double m = Math.abs(dx) + Math.abs(dy) * 0.9 + Math.abs(dz);
                        if (m > n + 0.2) continue;
                        String mat;
                        if (m < 0.8) mat = core;
                        else if (dy > 0) mat = top;
                        else if (dy < 0) mat = bot;
                        else mat = Math.floorMod(dx + dz, 2) == 0 ? bot : core;
                        put(x + dx, y + dy, z + dz, mat);
                    }
                }
            }
            put(x, y + n + 2, z, dat(spire));
        }

        // ═════════ Кольцо времени (обычный мир) ═════════

        /** Гигантское разбитое кольцо-циферблат над ареной, лучи света, стрелки времени и кристалл-ядро. */
        void halo() {
            int Y = 40;
            double Ro = R + 2, Ri = R - 3, mid = (Ri + Ro) / 2, hour = Math.PI / 6;
            double[][] bites = {{0.4, 3.6}, {2.3, 3.0}, {4.0, 3.4}, {5.3, 2.6}};
            for (int dx = -(int) Ro - 1; dx <= (int) Ro + 1; dx++) {
                for (int dz = -(int) Ro - 1; dz <= (int) Ro + 1; dz++) {
                    double r = Math.hypot(dx, dz);
                    if (r < Ri || r > Ro) continue;
                    boolean bitten = false;
                    for (double[] b : bites) {
                        if (Math.hypot(dx - mid * Math.cos(b[0]), dz - mid * Math.sin(b[0])) < b[1]) { bitten = true; break; }
                    }
                    if (bitten) {
                        if (rnd.nextDouble() < 0.22) {                         // летящие обломки
                            double push = 2 + rnd.nextDouble() * 6;
                            put(cx + (int) Math.round(dx + dx / r * push), y0 + Y + rnd.nextInt(12) - 5,
                                    cz + (int) Math.round(dz + dz / r * push), pick(DEBRIS));
                        }
                        continue;
                    }
                    double th = angle(dx, dz);
                    String m = (r > Ro - 1.0 || r < Ri + 0.9) ? pal.accent
                            : (Math.abs(th - Math.round(th / hour) * hour) * r < 0.7 ? pal.chiseled : pick(pal.white));
                    for (int dy = -1; dy <= 1; dy++) put(cx + dx, y0 + Y + dy, cz + dz, m);
                    if (Math.abs(th - Math.round(th / (Math.PI / 2)) * (Math.PI / 2)) * r < 1.0 && r > Ri + 0.9 && r < Ro - 1) {
                        put(cx + dx, y0 + Y + 2, cz + dz, pal.chiseled);       // крупные метки 12-3-6-9
                    }
                    if (Math.floorMod(dx + dz, 3) == 0 && r < Ri + 2) put(cx + dx, y0 + Y - 2, cz + dz, "SEA_LANTERN");   // огни снизу
                }
            }
            for (int k = 0; k < 160; k++) {                                    // светящееся внутреннее кольцо
                double ang = 2 * Math.PI * k / 160;
                put(cx + (int) Math.round((Ri - 1.5) * Math.cos(ang)), y0 + Y, cz + (int) Math.round((Ri - 1.5) * Math.sin(ang)), "SEA_LANTERN");
            }
            double pr = R - 2.5;                                               // лучи света от колонн к кольцу
            for (int k = 0; k < 8; k++) {
                double ang = 2 * Math.PI * k / 8 + Math.PI / 8;
                int px = cx + (int) Math.round(pr * Math.cos(ang)), pz = cz + (int) Math.round(pr * Math.sin(ang));
                for (int y = y0 + 19; y < y0 + Y - 2; y++) put(px, y, pz, pal.rod);
            }
            double[][] hands = {{18, Math.toRadians(-60), 31}, {11, Math.toRadians(-120), 32}};   // стрелки времени
            for (double[] h : hands) {
                for (int t = 3; t <= (int) h[0]; t++) {
                    put(cx + (int) Math.round(t * Math.cos(h[1])), y0 + (int) h[2], cz + (int) Math.round(t * Math.sin(h[1])), "SEA_LANTERN");
                }
            }
            crystal(cx, y0 + 24, cz, 3, "LIGHT_BLUE_CONCRETE", "BLUE_CONCRETE", "SEA_LANTERN", "minecraft:amethyst_cluster[facing=up]");
            for (int y = y0 + 6; y < y0 + 20; y++) put(cx, y, cz, pal.rod);
        }

        // ═════════ Змей эндера (Энд) ═════════

        String galaxy() {
            double t = rnd.nextDouble();
            if (t < 0.50) return "BLACK_CONCRETE";
            if (t < 0.68) return "OBSIDIAN";
            if (t < 0.78) return "PURPLE_CONCRETE";
            if (t < 0.85) return "CRYING_OBSIDIAN";
            if (t < 0.90) return "AMETHYST_BLOCK";
            if (t < 0.94) return "MAGENTA_CONCRETE";
            return "SEA_LANTERN";
        }

        /** Гигантский змей: тело-спираль вокруг арены, голова на севере смотрит на центр. */
        void serpent() {
            // расчищаем круг вокруг арены под змея
            int RC = R + 17;
            for (int dx = -RC; dx <= RC; dx++) {
                for (int dz = -RC; dz <= RC; dz++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (d <= R + 0.5 || d > RC + 0.5) continue;
                    for (int y = y0 + 1; y <= y0 + 34; y++) put(cx + dx, y, cz + dz, air);
                }
            }
            double phiEnd = -Math.PI / 2, turns = 1.25;
            int N = 520;
            Set<Long> body = new HashSet<>();
            double arc = 0, px = 0, py = 0, pz = 0;
            double hlx = 0, hlz = 0, hy = 0;
            for (int i = 0; i <= N; i++) {
                double t = (double) i / N;
                double phi = phiEnd - 2 * Math.PI * turns * (1 - t);
                double rho = (R + 12) - 5 * t;
                double y = 2.5 + 21 * Math.pow(t, 3.0) + 1.4 * Math.sin(2 * Math.PI * 3 * t);
                double r = 1.1 + 2.7 * Math.min(1.0, t / 0.35) + 0.3 * t;
                double lx = rho * Math.cos(phi), lz = rho * Math.sin(phi);
                if (i > 0) arc += Math.sqrt((lx - px) * (lx - px) + (y - py) * (y - py) + (lz - pz) * (lz - pz));
                px = lx; py = y; pz = lz;
                hlx = lx; hlz = lz; hy = y;
                int band = ((int) (arc / 3.2)) % 2;
                int Rr = (int) Math.ceil(r);
                for (int dx = -Rr; dx <= Rr; dx++) {
                    for (int dy = -Rr; dy <= Rr; dy++) {
                        for (int dz = -Rr; dz <= Rr; dz++) {
                            if (dx * dx + dy * dy + dz * dz > r * r) continue;
                            int[] w = a.rot((int) Math.round(lx) + dx, (int) Math.round(lz) + dz);
                            int wy = y0 + (int) Math.round(y) + dy;
                            if (!body.add(pack(cx + w[0], wy, cz + w[1]))) continue;
                            String m = dy < -0.45 * r ? (band == 1 ? "POLISHED_BLACKSTONE" : "DEEPSLATE") : galaxy();   // брюхо с рёбрами
                            put(cx + w[0], wy, cz + w[1], m);
                        }
                    }
                }
                if (((int) arc) % 5 == 0 && rnd.nextDouble() < 0.8 && r > 1.6) {                                      // спинные шипы
                    int kk = 2 + rnd.nextInt(2);
                    for (int k = 1; k < kk; k++) putL((int) Math.round(lx), (int) Math.round(y + r + k - 1) , (int) Math.round(lz), "OBSIDIAN");
                }
            }
            head(hlx, hlz, hy);
        }

        /** Голова змея: стеклянный череп с галактикой, светящаяся пасть с зубами, глаза-чёрные дыры. */
        void head(double hlx, double hlz, double hy) {
            // локальная система головы: a - вперёд (к центру арены, +z), b - вверх, c - вбок (+x)
            ellipsoid(hlx, hlz, hy, 2.0, 1.8, 0, 7.6, 4.6, 5.2, (d) -> d > 0.74 ? "WHITE_STAINED_GLASS" : galaxy());
            ellipsoid(hlx, hlz, hy, 3.2, -3.0, 0, 6.4, 1.9, 4.0, (d) -> rnd.nextBoolean() ? "BLACK_CONCRETE" : "OBSIDIAN");
            ellipsoid(hlx, hlz, hy, 1.0, -0.9, 0, 3.2, 1.1, 2.2, (d) -> rnd.nextDouble() < 0.2 ? "SEA_LANTERN" : "MAGENTA_CONCRETE");
            double[] cs = {-3.6, -1.8, 0, 1.8, 3.6};
            for (int aa = 2; aa < 10; aa++) {
                for (double c : cs) {
                    for (int b = -2; b >= -3; b--) putHead(hlx, hlz, hy, aa, b, c, "WHITE_CONCRETE");
                    if (Math.abs(c) >= 1.8) putHead(hlx, hlz, hy, aa, -1, c, "WHITE_CONCRETE");
                }
            }
            for (int c : new int[]{-1, 1}) {
                for (int k = 0; k < 3; k++) putHead(hlx, hlz, hy, -5 - k, 3 + k, c * 2.2, "OBSIDIAN");
            }
            for (int side : new int[]{-1, 1}) {                                // глаза-чёрные дыры
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        for (int dz = -3; dz <= 3; dz++) {
                            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                            if (d <= 1.55) putHeadRel(hlx, hlz, hy, 0.5 + dz, 1.8 + dy, side * 5.4 + dx, "BLACK_CONCRETE");
                            else if (d <= 2.15) putHeadRel(hlx, hlz, hy, 0.5 + dz, 1.8 + dy, side * 5.4 + dx, "MAGENTA_CONCRETE");
                        }
                    }
                }
            }
        }

        void putHead(double hlx, double hlz, double hy, double aa, double b, double c, String mat) {
            putL((int) Math.round(hlx + c), (int) Math.round(hy + b), (int) Math.round(hlz + aa), mat);
        }
        void putHeadRel(double hlx, double hlz, double hy, double aa, double b, double c, String mat) { putHead(hlx, hlz, hy, aa, b, c, mat); }

        void ellipsoid(double hlx, double hlz, double hy, double A, double B, double C, double ra, double rb, double rc,
                       java.util.function.DoubleFunction<String> fn) {
            for (int aa = (int) (A - ra) - 1; aa <= (int) (A + ra) + 1; aa++) {
                for (int b = (int) (B - rb) - 1; b <= (int) (B + rb) + 1; b++) {
                    for (int c = (int) (C - rc) - 1; c <= (int) (C + rc) + 1; c++) {
                        double d = Math.pow((aa - A) / ra, 2) + Math.pow((b - B) / rb, 2) + Math.pow((c - C) / rc, 2);
                        if (d <= 1.0) putHead(hlx, hlz, hy, aa, b, c, fn.apply(d));
                    }
                }
            }
        }

        /** Парящие кристаллы эндера (подальше от змея и построек). */
        void bigCrystals() {
            int placed = 0, tries = 0;
            while (placed < 14 && tries < 400) {
                tries++;
                double ang = rnd.nextDouble() * 2 * Math.PI;
                double d = F + 2 + rnd.nextDouble() * (R + 10 - (F + 2));
                int x = cx + (int) Math.round(Math.cos(ang) * d), z = cz + (int) Math.round(Math.sin(ang) * d);
                int y = y0 + 8 + rnd.nextInt(22);
                int n = rnd.nextBoolean() ? 2 : 3;
                boolean free = true;
                for (int i = -4; i <= 4 && free; i += 2) {
                    for (int j = -4; j <= 4 && free; j += 2) {
                        for (int k = -4; k <= 4 && free; k += 2) {
                            BlockData bd = plan.get(pack(x + i, y + j, z + k));
                            if (bd != null && !air.equals(bd)) free = false;
                        }
                    }
                }
                if (!free) continue;
                crystal(x, y, z, n, "AMETHYST_BLOCK", "OBSIDIAN", "CRYING_OBSIDIAN", "minecraft:amethyst_cluster[facing=up]");
                placed++;
            }
        }

        // ═════════ Кальдера (Нижний мир) ═════════

        /** Кратер вулкана: чёрный камень и базальт, светящиеся трещины, скалы-стены, рёбра гигантского скелета. */
        void caldera() {
            int RC = R + 9;
            String[] basalts = {"BLACKSTONE", "BASALT", "SMOOTH_BASALT", "POLISHED_BASALT", "NETHERRACK"};
            String[] rockMix = {"BLACKSTONE", "BASALT", "SMOOTH_BASALT", "POLISHED_BLACKSTONE_BRICKS"};
            double[] ph = {rnd.nextDouble() * 6.28, rnd.nextDouble() * 6.28, rnd.nextDouble() * 6.28};
            Map<Long, Integer> rimTop = new HashMap<>();
            for (int dx = -RC; dx <= RC; dx++) {
                for (int dz = -RC; dz <= RC; dz++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    if (d > RC + 0.5) continue;
                    int x = cx + dx, z = cz + dz;
                    for (int y = y0 + 30; y > y0; y--) put(x, y, z, air);       // расчищаем пещеру под кратер

                    // дно и основание
                    String fb;
                    if (d <= F + 0.5) {
                        if (d <= 7) {
                            fb = d >= 5 ? "CRYING_OBSIDIAN" : "OBSIDIAN";
                            if (d < 2.4) fb = "GLOWSTONE";
                        } else {
                            boolean crack = Math.abs(Math.sin(0.5 * dx + 1.1 * Math.sin(0.33 * dz))) < 0.07 && d > 9;   // "лавовые" трещины (безопасные)
                            fb = crack ? "SHROOMLIGHT" : pick(basalts);
                        }
                    } else {
                        fb = "BLACKSTONE";
                    }
                    put(x, y0, z, fb);
                    if (d <= 4.0) put(x, y0 + 1, z, "OBSIDIAN");                // помост босса
                    for (int k = 1; k <= 3; k++) put(x, y0 - k, z, "BLACKSTONE");

                    // неровная каменная стена кратера с проходами на четырёх осях
                    if (d > F + 0.5 && d <= R + 8) {
                        double th = angle(dx, dz);
                        double nz = Math.sin(3 * th + ph[0]) + 0.8 * Math.sin(7 * th + ph[1]) + 0.5 * Math.sin(13 * th + ph[2]);
                        double hgt = 3 + (d - F) * 0.8 + 3.2 * (nz + 2.3) / 2.5 + rnd.nextDouble() * 1.5;
                        hgt = Math.min(hgt, 18) * Math.min(1.0, (R + 8.5 - d) / 4);
                        if (corridor(dx, dz) && d < R + 4) hgt = Math.min(hgt, 1 + (d - F) * 0.25);
                        int h = (int) hgt;
                        for (int y = 1; y <= h; y++) {
                            String m = rnd.nextDouble() < 0.02 ? "MAGMA_BLOCK" : pick(rockMix);
                            put(x, y0 + y, z, m);
                        }
                        if (h >= 1) {
                            if (rnd.nextDouble() < 0.3) put(x, y0 + h, z, "NETHERRACK");
                            rimTop.put(col(x, z), h);
                        }
                    }
                }
            }
            // базальтовые шпили с душевыми фонарями
            for (int i = 0; i < 9; i++) {
                double ang = rnd.nextDouble() * 2 * Math.PI, d = F + 6 + rnd.nextDouble() * (R + 5 - (F + 6));
                int x = cx + (int) Math.round(Math.cos(ang) * d), z = cz + (int) Math.round(Math.sin(ang) * d);
                if (corridor(x - cx, z - cz)) continue;
                int hh = 14 + rnd.nextInt(11);
                for (int y = 1; y <= hh; y++) {
                    int w = y < hh * 0.55 ? 2 : (y < hh * 0.85 ? 1 : 0);
                    for (int ix = -w; ix <= w; ix++) for (int iz = -w; iz <= w; iz++) put(x + ix, y0 + y, z + iz, pick(rockMix));
                }
                put(x, y0 + hh + 1, z, dat("minecraft:soul_lantern[hanging=false]"));
            }
            // "лавопады" по краю кратера (из безопасных светящихся блоков)
            for (int k = 0; k < 7; k++) {
                double ang = k * 2 * Math.PI / 7 + 0.4;
                int x = cx + (int) Math.round((R + 6) * Math.cos(ang)), z = cz + (int) Math.round((R + 6) * Math.sin(ang));
                Integer top = rimTop.get(col(x, z));
                if (top == null || top < 4 || corridor(x - cx, z - cz)) continue;
                for (int y = 1; y <= top + 1; y++) put(x, y0 + y, z, y % 3 == 0 ? "MAGMA_BLOCK" : "SHROOMLIGHT");
            }
            // гигантские рёбра-арки из костей над ареной
            for (int k = 0; k < 5; k++) {
                double al = k * Math.PI / 5 + 0.3;
                for (int i = 0; i < 80; i++) {
                    double phi = Math.PI * i / 79;
                    double c = (F + 6) * Math.cos(phi), y = 2 + (F + 4) * Math.sin(phi) * 0.95;
                    int bx = cx + (int) Math.round(c * Math.cos(al)), bz = cz + (int) Math.round(c * Math.sin(al)), by = y0 + (int) Math.round(y);
                    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                        if (dx * dx + dy * dy + dz * dz <= 2) put(bx + dx, by + dy, bz + dz, "BONE_BLOCK");
                    }
                }
            }
            // багровые грибы на гребне кратера
            int fungi = 0;
            for (int t = 0; t < 400 && fungi < 6; t++) {
                double ang = rnd.nextDouble() * 2 * Math.PI, d = R + 3 + rnd.nextDouble() * 4;
                int x = cx + (int) Math.round(Math.cos(ang) * d), z = cz + (int) Math.round(Math.sin(ang) * d);
                Integer top = rimTop.get(col(x, z));
                if (top == null || top < 6 || corridor(x - cx, z - cz)) continue;
                int base = y0 + top;
                int h = 6 + rnd.nextInt(4);
                for (int y = 1; y <= h; y++) put(x, base + y, z, "CRIMSON_STEM");
                for (int ix = -4; ix <= 4; ix++) for (int iy = -1; iy <= 2; iy++) for (int iz = -4; iz <= 4; iz++) {
                    double dd = Math.sqrt(ix * ix + (iy * 1.6) * (iy * 1.6) + iz * iz);
                    if (dd <= 3.8 && (iy >= 0 || dd <= 2.4)) put(x + ix, base + h + iy + 1, z + iz, rnd.nextDouble() < 0.1 ? "SHROOMLIGHT" : "NETHER_WART_BLOCK");
                }
                fungi++;
            }
        }
    }
}

package com.example.bossarena;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Расписание боссов по дням недели: автоспавн, оповещения, календарь для игроков (Ray095).
 *
 * Слушатель регистрируется ОДИН раз в BossArena.onEnable (раньше регистрировался дважды).
 */
public class CalendarManager implements Listener {

    public static final String[] DAYS = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
    public static final String[] DAYS_RU = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"};
    public static final String[] DAYS_SHORT = {"ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"};

    /** Сообщение по умолчанию. Можно использовать {BOSS} и {TIME}. */
    public static final String DEFAULT_MESSAGE = "&a&lСегодня в {TIME} появится &r{BOSS}&a&l!";

    private static final DateTimeFormatter TIME_IN = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter TIME_OUT = DateTimeFormatter.ofPattern("HH:mm");

    private final JavaPlugin plugin;
    private final BossManager bossManager;
    private final Map<String, List<BossEvent>> schedule = new LinkedHashMap<>();

    // ключи вида 2026-11-07_12:00_zombie_boss
    private final Set<String> announced = new HashSet<>();
    private final Set<String> spawned = new HashSet<>();
    private boolean zoneWarned = false;

    public CalendarManager(JavaPlugin plugin, BossManager bossManager) {
        this.plugin = plugin;
        this.bossManager = bossManager;
        loadSchedule();
        startScheduler();
    }

    // ───────────────────────── Данные ─────────────────────────

    public void loadSchedule() {
        schedule.clear();
        for (String d : DAYS) schedule.put(d, new ArrayList<>());

        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("calendar.schedule");
        if (sec == null) return;

        for (String day : sec.getKeys(false)) {
            List<BossEvent> list = schedule.get(day.toLowerCase());
            if (list == null) continue;
            for (Map<?, ?> m : sec.getMapList(day)) {
                Object t = m.get("time");
                Object b = m.get("boss");
                Object msg = m.get("message");
                if (t == null || b == null) continue;
                String time = normalizeTime(String.valueOf(t));
                if (time == null) {
                    plugin.getLogger().warning("❌ Календарь: неверное время '" + t + "' (нужно ЧЧ:ММ), событие пропущено.");
                    continue;
                }
                list.add(new BossEvent(time, String.valueOf(b), msg == null ? DEFAULT_MESSAGE : String.valueOf(msg)));
            }
            sortEvents(list);
        }
    }

    /** Записать расписание в config.yml. */
    public void saveSchedule() {
        plugin.getConfig().set("calendar.schedule", null);
        for (String day : DAYS) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (BossEvent e : getEvents(day)) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("time", e.time);
                map.put("boss", e.boss);
                map.put("message", e.message);
                list.add(map);
            }
            plugin.getConfig().set("calendar.schedule." + day, list);
        }
        plugin.saveConfig();
    }

    public List<BossEvent> getEvents(String day) {
        return schedule.computeIfAbsent(day, k -> new ArrayList<>());
    }

    public Map<String, List<BossEvent>> getSchedule() { return schedule; }

    public static void sortEvents(List<BossEvent> list) {
        list.sort(Comparator.comparing((BossEvent e) -> e.time).thenComparing(e -> e.boss));
    }

    /** "9:5" не пройдёт, "9:05" -> "09:05", иначе null. */
    public static String normalizeTime(String s) {
        try {
            return LocalTime.parse(s.trim(), TIME_IN).format(TIME_OUT);
        } catch (Exception e) {
            return null;
        }
    }

    // ───────────────────────── Время ─────────────────────────

    /** Часовой пояс: calendar.timezone в конфиге (например Europe/Moscow), пусто = как у сервера. */
    public ZoneId getZone() {
        String tz = plugin.getConfig().getString("calendar.timezone", "");
        if (tz != null && !tz.isBlank()) {
            try {
                return ZoneId.of(tz.trim());
            } catch (Exception e) {
                if (!zoneWarned) {
                    plugin.getLogger().warning("❌ Неверный calendar.timezone '" + tz + "', используется время сервера.");
                    zoneWarned = true;
                }
            }
        }
        return ZoneId.systemDefault();
    }

    public ZonedDateTime now() { return ZonedDateTime.now(getZone()); }

    public String todayKey() { return now().getDayOfWeek().name().toLowerCase(); }

    // ───────────────────────── Автоспавн ─────────────────────────

    private void startScheduler() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("auto_spawn.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("calendar.enabled", true)) return;

        ZonedDateTime now = now();
        LocalDate today = now.toLocalDate();
        boolean announce = plugin.getConfig().getBoolean("auto_spawn.announce_before_spawn", true);
        int announceSec = Math.max(1, plugin.getConfig().getInt("auto_spawn.announce_time", 30));

        // чистим старые ключи (даты в начале ключа сравниваются как строки)
        String todayStr = today.toString();
        announced.removeIf(k -> k.compareTo(todayStr) < 0);
        spawned.removeIf(k -> k.compareTo(todayStr) < 0);

        // сегодня и завтра: чтобы оповещение за N секунд работало и для событий в 00:00
        for (int offset = 0; offset <= 1; offset++) {
            LocalDate date = today.plusDays(offset);
            String dayKey = date.getDayOfWeek().name().toLowerCase();
            for (BossEvent ev : new ArrayList<>(getEvents(dayKey))) {
                String time = normalizeTime(ev.time);
                if (time == null) continue;
                ZonedDateTime at = ZonedDateTime.of(date, LocalTime.parse(time, TIME_OUT), getZone());
                long secondsUntil = ChronoUnit.SECONDS.between(now, at);
                String key = date + "_" + time + "_" + ev.boss;

                if (announce && secondsUntil > 0 && secondsUntil <= announceSec && announced.add(key)) {
                    announceEvent(ev, (int) secondsUntil);
                }
                // окно 60 секунд: если сервер включили ровно в момент события, босс всё равно появится
                if (offset == 0 && secondsUntil <= 0 && secondsUntil > -60 && spawned.add(key)) {
                    spawnEvent(ev);
                }
            }
        }
    }

    private void announceEvent(BossEvent ev, int seconds) {
        Bukkit.broadcast(Component.text(GuiUtil.color(formatMessage(ev))));
        Title title = Title.title(
                GuiUtil.title(getBossName(ev.boss)),
                GuiUtil.title("&eпоявится через &6&l" + seconds + " &eсек!"),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(600)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
        }
    }

    private void spawnEvent(BossEvent ev) {
        if (bossManager.isBossAlive()) bossManager.forceBossDespawn();
        // false: босс из календаря не запускает ротацию после смерти
        boolean ok = bossManager.spawnBossById(ev.boss, false);
        if (!ok) {
            plugin.getLogger().warning("❌ Календарь: не удалось заспавнить босса '" + ev.boss
                    + "' (нет такого босса, неверная точка спавна или мир не загружен).");
            return;
        }
        String message = plugin.getConfig().getString("messages.calendar_spawned",
                "&a✅ Босс {BOSS} появился!");
        message = message.replace("{BOSS}", getBossName(ev.boss));
        Bukkit.broadcast(Component.text(GuiUtil.color(message)));
    }

    private String formatMessage(BossEvent ev) {
        String msg = (ev.message == null || ev.message.isBlank()) ? DEFAULT_MESSAGE : ev.message;
        return msg.replace("{BOSS}", getBossName(ev.boss)).replace("{TIME}", ev.time);
    }

    public String getBossName(String bossId) {
        String name = plugin.getConfig().getString("bosses." + bossId + ".boss.name");
        return name == null ? bossId : ChatColor.translateAlternateColorCodes('&', name);
    }

    // ───────────────────────── Календарь для игроков ─────────────────────────

    private static class CalendarHolder implements InventoryHolder {
        Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    public void openCalendar(Player player) {
        String title = plugin.getConfig().getString("calendar.title", "&6&l📅 Календарь событий");
        CalendarHolder holder = new CalendarHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, GuiUtil.title(title));
        holder.inventory = inv;

        ZonedDateTime now = now();
        DayOfWeek today = now.getDayOfWeek();
        DayOfWeek tomorrow = today.plus(1);

        inv.setItem(0, GuiUtil.item(Material.LIME_WOOL, "&a&l📅 Сегодня &7— " + DAYS_RU[today.getValue() - 1],
                "&7Сейчас: &f" + now.format(TIME_OUT)));
        inv.setItem(4, GuiUtil.item(Material.YELLOW_WOOL, "&e&l📅 Завтра &7— " + DAYS_RU[tomorrow.getValue() - 1]));
        inv.setItem(8, GuiUtil.item(Material.GRAY_WOOL, "&7📅 Будущие события"));

        fillDay(inv, today, 0, 9, 9);
        fillDay(inv, tomorrow, 1, 18, 9);

        // следующие дни (2..7), на 8-й день недели уже "сегодня", поэтому дальше не идём
        int slot = 27;
        boolean any = false;
        for (int i = 2; i <= 7 && slot < 54; i++) {
            DayOfWeek d = today.plus(i);
            for (BossEvent ev : getEvents(d.name().toLowerCase())) {
                if (slot >= 54) break;
                inv.setItem(slot++, eventItem(ev, i, d));
                any = true;
            }
        }
        if (!any) inv.setItem(27, GuiUtil.item(Material.BARRIER, "&7Нет событий"));

        GuiUtil.fillEmpty(inv, Material.BLACK_STAINED_GLASS_PANE);
        player.openInventory(inv);
    }

    private void fillDay(Inventory inv, DayOfWeek day, int offset, int startSlot, int max) {
        List<BossEvent> events = getEvents(day.name().toLowerCase());
        int slot = startSlot;
        for (BossEvent ev : events) {
            if (slot >= startSlot + max) break;
            inv.setItem(slot++, eventItem(ev, offset, day));
        }
        if (events.isEmpty()) inv.setItem(startSlot, GuiUtil.item(Material.BARRIER, "&7Нет событий"));
    }

    private ItemStack eventItem(BossEvent ev, int offset, DayOfWeek day) {
        String bossName = getBossName(ev.boss);
        String color = offset == 0 ? "&a" : offset == 1 ? "&e" : "&7";
        List<String> lore = new ArrayList<>();
        lore.add("&7День: &f" + DAYS_RU[day.getValue() - 1]);
        lore.add("&7Время: &f" + ev.time);
        lore.add("&7Босс: &f" + bossName);
        lore.add("&7Появится: &f" + until(offset, ev.time));
        return GuiUtil.item(Material.CHEST,
                color + (offset >= 2 ? DAYS_SHORT[day.getValue() - 1] + " " : "") + ev.time + " &7— " + bossName, lore);
    }

    private String until(int offset, String time) {
        String t = normalizeTime(time);
        if (t == null) return "&8неизвестно";
        ZonedDateTime now = now();
        ZonedDateTime target = ZonedDateTime.of(now.toLocalDate().plusDays(offset), LocalTime.parse(t, TIME_OUT), getZone());
        long mins = Duration.between(now, target).toMinutes();
        if (mins < 0) return "&8сегодня уже было";
        long d = mins / 1440, h = (mins % 1440) / 60, m = mins % 60;
        StringBuilder sb = new StringBuilder("через ");
        if (d > 0) sb.append(d).append(" дн ");
        if (h > 0) sb.append(h).append(" ч ");
        sb.append(m).append(" мин");
        return sb.toString();
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CalendarHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CalendarHolder) event.setCancelled(true);
    }

    // ───────────────────────── Событие расписания ─────────────────────────

    public static class BossEvent {
        public String time;
        public String boss;
        public String message;

        public BossEvent(String time, String boss, String message) {
            this.time = time;
            this.boss = boss;
            this.message = message;
        }
    }
}

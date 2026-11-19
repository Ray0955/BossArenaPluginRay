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

public class CalendarManager implements Listener {

    public static final String[] DAYS = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
    public static final String[] DAYS_RU = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"};
    public static final String[] DAYS_SHORT = {"ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"};

    public static final String DEFAULT_MESSAGE = "&a&lСегодня в {TIME} появится &r{BOSS}&a&l!";

    private static final DateTimeFormatter TIME_IN = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter TIME_OUT = DateTimeFormatter.ofPattern("HH:mm");

    private final JavaPlugin plugin;
    private final BossManager bossManager;
    private final Map<String, List<BossEvent>> schedule = new LinkedHashMap<>();

    private final Set<String> announced = new HashSet<>();
    private final Set<String> spawned = new HashSet<>();
    private boolean zoneWarned = false;

    public CalendarManager(JavaPlugin plugin, BossManager bossManager) {
        this.plugin = plugin;
        this.bossManager = bossManager;
        loadSchedule();
        startScheduler();
    }

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

    public static String normalizeTime(String s) {
        try {
            return LocalTime.parse(s.trim(), TIME_IN).format(TIME_OUT);
        } catch (Exception e) {
            return null;
        }
    }

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

    private void startScheduler() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20
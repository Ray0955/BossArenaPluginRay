package com.example.bossarena;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * Админ-календарь: редактирование расписания боссов прямо в игре (Ray095).
 *
 * Все изменения сразу записываются в config.yml.
 * Меню распознаются по InventoryHolder, а не по названию.
 * Слушатель регистрируется один раз в BossArena.onEnable.
 */
public class CalendarAdminGUI implements Listener {

    private enum Type { MAIN, DAY, EDIT, CONFIRM }

    private static final int[] BOSS_SLOTS = {14, 15, 16, 23, 24, 25, 32, 33, 34};
    private static final int MAX_EVENTS_PER_DAY = 36;

    private final JavaPlugin plugin;
    private final CalendarManager calendar;
    private final Map<UUID, EditSession> sessions = new HashMap<>();
    // чат приходит из другого потока, поэтому потокобезопасный набор
    private final Set<UUID> awaitingChat = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public CalendarAdminGUI(JavaPlugin plugin, CalendarManager calendar) {
        this.plugin = plugin;
        this.calendar = calendar;
    }

    // ───────────────────────── Типы данных ─────────────────────────

    private static class AdminHolder implements InventoryHolder {
        final Type type;
        final String day;
        Runnable onYes;
        Runnable onNo;
        Inventory inventory;

        AdminHolder(Type type, String day) {
            this.type = type;
            this.day = day;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private static class EditSession {
        final String day;
        final int index;      // -1 = новое событие
        int minutes;          // 0..1439
        String boss;
        String message;
        int bossPage = 0;

        EditSession(String day, int index, int minutes, String boss, String message) {
            this.day = day;
            this.index = index;
            this.minutes = minutes;
            this.boss = boss;
            this.message = message;
        }

        String time() { return String.format("%02d:%02d", minutes / 60, minutes % 60); }

        void shift(int delta) { minutes = ((minutes + delta) % 1440 + 1440) % 1440; }
    }

    // ───────────────────────── Главное меню ─────────────────────────

    public void openMainMenu(Player player) {
        AdminHolder holder = new AdminHolder(Type.MAIN, null);
        Inventory inv = Bukkit.createInventory(holder, 54, GuiUtil.title("&4&l⚙ Админ-календарь"));
        holder.inventory = inv;

        GuiUtil.frame(inv, Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE);

        inv.setItem(4, GuiUtil.glow(GuiUtil.item(Material.ENCHANTED_BOOK, "&6&l📖 Расписание боссов",
                "&7Время сервера: &f" + calendar.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                        + " &8(" + calendar.getZone() + ")",
                "&7Изменения сохраняются сразу в config.yml",
                "",
                "&6ЛКМ по дню: &fоткрыть",
                "&6ПКМ по дню: &fдобавить событие")));

        for (int i = 0; i < CalendarManager.DAYS.length; i++) inv.setItem(10 + i, dayItem(i));

        inv.setItem(45, GuiUtil.item(Material.BOOK, "&e&l🔄 Перечитать config.yml",
                "&7Загружает расписание из файла заново"));
        inv.setItem(47, GuiUtil.item(Material.CLOCK, "&b&l📅 Календарь игроков",
                "&7Посмотреть, как видят игроки"));
        inv.setItem(49, GuiUtil.item(Material.BARRIER, "&c&l✖ Закрыть"));
        inv.setItem(53, GuiUtil.item(Material.REDSTONE, "&c&l🗑 Очистить всё расписание",
                "&7Потребуется подтверждение"));

        GuiUtil.fillEmpty(inv, Material.BLACK_STAINED_GLASS_PANE);
        player.openInventory(inv);
    }

    private ItemStack dayItem(int i) {
        String day = CalendarManager.DAYS[i];
        List<CalendarManager.BossEvent> events = calendar.getEvents(day);
        boolean isToday = day.equals(calendar.todayKey());
        int count = events.size();

        Material material = count > 0 ? (isToday ? Material.LIME_WOOL : Material.GREEN_WOOL)
                : (isToday ? Material.YELLOW_WOOL : Material.GRAY_WOOL);
        String color = count > 0 ? (isToday ? "&a" : "&2") : (isToday ? "&e" : "&7");

        List<String> lore = new ArrayList<>();
        lore.add("&7Событий: &f" + count);
        for (CalendarManager.BossEvent e : events) lore.add("  &e" + e.time + " &7— " + calendar.getBossName(e.boss));
        lore.add("");
        lore.add("&6ЛКМ: &fоткрыть день");
        lore.add("&6ПКМ: &fдобавить событие");

        return GuiUtil.item(material,
                color + "&l" + CalendarManager.DAYS_RU[i] + (isToday ? " &a&l(СЕГОДНЯ)" : ""), lore);
    }

    // ───────────────────────── Меню дня ─────────────────────────

    private void openDayMenu(Player player, String day) {
        int di = Arrays.asList(CalendarManager.DAYS).indexOf(day);
        AdminHolder holder = new AdminHolder(Type.DAY, day);
        Inventory inv = Bukkit.createInventory(holder, 54, GuiUtil.title("&6&l📅 " + CalendarManager.DAYS_RU[di]));
        holder.inventory = inv;

        List<CalendarManager.BossEvent> events = calendar.getEvents(day);
        for (int i = 0; i < events.size() && i < MAX_EVENTS_PER_DAY; i++) {
            CalendarManager.BossEvent e = events.get(i);
            inv.setItem(i, GuiUtil.item(Material.CHEST,
                    "&e" + e.time + " &7— " + calendar.getBossName(e.boss),
                    "&7Босс: &f" + e.boss,
                    "&7Сообщение: &f" + shorten(e.message),
                    "",
                    "&6ЛКМ: &fредактировать",
                    "&cПКМ: &fудалить"));
        }

        inv.setItem(45, GuiUtil.item(Material.LIME_WOOL, "&a&l➕ Добавить событие"));
        inv.setItem(46, GuiUtil.item(Material.RED_WOOL, "&c&l🗑 Очистить день", "&7Потребуется подтверждение"));
        inv.setItem(49, GuiUtil.item(Material.BOOK, "&7&l📋 Событий: &f" + events.size() + "&7/" + MAX_EVENTS_PER_DAY));
        inv.setItem(53, GuiUtil.item(Material.ARROW, "&7&l🔙 Назад"));

        GuiUtil.fillEmpty(inv, Material.BLACK_STAINED_GLASS_PANE);
        player.openInventory(inv);
    }

    // ───────────────────────── Редактор события ─────────────────────────

    private void startEdit(Player player, String day, int index) {
        List<CalendarManager.BossEvent> events = calendar.getEvents(day);
        EditSession s;
        if (index >= 0 && index < events.size()) {
            CalendarManager.BossEvent e = events.get(index);
            String t = CalendarManager.normalizeTime(e.time);
            int minutes = 12 * 60;
            if (t != null) minutes = Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3));
            s = new EditSession(day, index, minutes, e.boss, e.message);
        } else {
            if (events.size() >= MAX_EVENTS_PER_DAY) {
                player.sendMessage(ChatColor.RED + "❌ В одном дне не больше " + MAX_EVENTS_PER_DAY + " событий!");
                return;
            }
            List<String> ids = bossIds();
            s = new EditSession(day, -1, 12 * 60, ids.isEmpty() ? "" : ids.get(0), CalendarManager.DEFAULT_MESSAGE);
        }
        int pos = bossIds().indexOf(s.boss);
        if (pos >= 0) s.bossPage = pos / BOSS_SLOTS.length;
        sessions.put(player.getUniqueId(), s);
        openEdit(player);
    }

    private void openEdit(Player player) {
        EditSession s = sessions.get(player.getUniqueId());
        if (s == null) { openMainMenu(player); return; }

        int di = Arrays.asList(CalendarManager.DAYS).indexOf(s.day);
        AdminHolder holder = new AdminHolder(Type.EDIT, s.day);
        Inventory inv = Bukkit.createInventory(holder, 54, GuiUtil.title(
                (s.index < 0 ? "&5&l➕ Новое событие" : "&5&l✏ Редактирование")
                        + " &8— " + CalendarManager.DAYS_RU[di]));
        holder.inventory = inv;

        GuiUtil.frame(inv, Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE);

        // сводка
        inv.setItem(4, GuiUtil.glow(GuiUtil.item(Material.CHEST, "&e&l" + s.time() + " &7— " + calendar.getBossName(s.boss),
                "&7День: &f" + CalendarManager.DAYS_RU[di],
                "&7Сообщение: &f" + shorten(s.message))));

        // время
        inv.setItem(10, GuiUtil.item(Material.LIME_STAINED_GLASS_PANE, "&a⬆ +1 час"));
        inv.setItem(11, GuiUtil.item(Material.LIME_STAINED_GLASS_PANE, "&a⬆ +10 минут"));
        inv.setItem(12, GuiUtil.item(Material.LIME_STAINED_GLASS_PANE, "&a⬆ +1 минута"));
        inv.setItem(20, GuiUtil.item(Material.CLOCK, "&b&l🕐 Время: &f" + s.time(),
                "&7Часовой пояс: &f" + calendar.getZone()));
        inv.setItem(28, GuiUtil.item(Material.RED_STAINED_GLASS_PANE, "&c⬇ −1 час"));
        inv.setItem(29, GuiUtil.item(Material.RED_STAINED_GLASS_PANE, "&c⬇ −10 минут"));
        inv.setItem(30, GuiUtil.item(Material.RED_STAINED_GLASS_PANE, "&c⬇ −1 минута"));

        // боссы
        List<String> ids = bossIds();
        int pages = Math.max(1, (ids.size() + BOSS_SLOTS.length - 1) / BOSS_SLOTS.length);
        s.bossPage = Math.max(0, Math.min(s.bossPage, pages - 1));
        for (int k = 0; k < BOSS_SLOTS.length; k++) {
            int idx = s.bossPage * BOSS_SLOTS.length + k;
            if (idx >= ids.size()) break;
            String id = ids.get(idx);
            boolean selected = id.equals(s.boss);
            ItemStack it = GuiUtil.item(bossIcon(id),
                    (selected ? "&a&l✔ " : "&7") + calendar.getBossName(id),
                    "&7ID: &f" + id,
                    selected ? "&aВыбран" : "&6ЛКМ: &fвыбрать");
            if (selected) GuiUtil.glow(it);
            inv.setItem(BOSS_SLOTS[k], it);
        }
        inv.setItem(22, GuiUtil.item(Material.WITHER_SKELETON_SKULL, "&c&l👾 Босс: &f" + calendar.getBossName(s.boss),
                "&7Страница боссов: &f" + (s.bossPage + 1) + "/" + pages));
        if (s.bossPage > 0) inv.setItem(13, GuiUtil.item(Material.ARROW, "&e◀ Предыдущие боссы"));
        if (s.bossPage < pages - 1) inv.setItem(31, GuiUtil.item(Material.ARROW, "&eСледующие боссы ▶"));

        // сообщение
        inv.setItem(39, GuiUtil.item(Material.WRITABLE_BOOK, "&e&l📝 Сообщение",
                "&7" + s.message,
                "",
                "&6ЛКМ: &fввести новое в чате",
                "&6ПКМ: &fсбросить на стандартное",
                "&8Можно использовать {BOSS} и {TIME}"));

        // кнопки
        inv.setItem(45, GuiUtil.item(Material.BARRIER, "&c&l❌ Отмена", "&7Выйти без сохранения"));
        inv.setItem(49, GuiUtil.item(Material.LIME_WOOL, "&a&l✅ Сохранить"));
        if (s.index >= 0) inv.setItem(53, GuiUtil.item(Material.RED_WOOL, "&c&l🗑 Удалить событие"));

        GuiUtil.fillEmpty(inv, Material.BLACK_STAINED_GLASS_PANE);
        player.openInventory(inv);
    }

    private void saveEdit(Player player, EditSession s) {
        List<String> ids = bossIds();
        if (s.boss == null || !ids.contains(s.boss)) {
            player.sendMessage(ChatColor.RED + "❌ Выберите босса!");
            return;
        }
        List<CalendarManager.BossEvent> events = calendar.getEvents(s.day);
        String time = s.time();
        for (int i = 0; i < events.size(); i++) {
            CalendarManager.BossEvent e = events.get(i);
            if (i != s.index && e.time.equals(time) && e.boss.equals(s.boss)) {
                player.sendMessage(ChatColor.RED + "❌ Такое событие (время и босс) уже есть в этом дне!");
                return;
            }
        }
        CalendarManager.BossEvent ev = new CalendarManager.BossEvent(time, s.boss, s.message);
        if (s.index >= 0 && s.index < events.size()) events.set(s.index, ev);
        else events.add(ev);
        CalendarManager.sortEvents(events);
        calendar.saveSchedule();

        sessions.remove(player.getUniqueId());
        player.sendMessage(ChatColor.GREEN + "✅ Событие сохранено: " + time + " — " + ChatColor.stripColor(calendar.getBossName(s.boss)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        openDayMenu(player, s.day);
    }

    // ───────────────────────── Подтверждение ─────────────────────────

    private void openConfirm(Player player, String text, Runnable yes, Runnable no) {
        AdminHolder holder = new AdminHolder(Type.CONFIRM, null);
        holder.onYes = yes;
        holder.onNo = no;
        Inventory inv = Bukkit.createInventory(holder, 27, GuiUtil.title("&4&l⚠ Подтверждение"));
        holder.inventory = inv;
        inv.setItem(13, GuiUtil.item(Material.PAPER, "&e&l" + text, "&7Это действие нельзя отменить"));
        inv.setItem(11, GuiUtil.item(Material.LIME_WOOL, "&a&l✅ Да"));
        inv.setItem(15, GuiUtil.item(Material.RED_WOOL, "&c&l❌ Нет"));
        GuiUtil.fillEmpty(inv, Material.BLACK_STAINED_GLASS_PANE);
        player.openInventory(inv);
    }

    // ───────────────────────── Клики ─────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof AdminHolder holder)) return;
        event.setCancelled(true);                                  // ничего нельзя вынести или положить
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;

        int slot = event.getSlot();
        boolean left = event.isLeftClick();
        boolean right = event.isRightClick();

        switch (holder.type) {
            case MAIN -> clickMain(player, slot, left, right);
            case DAY -> clickDay(player, holder.day, slot, left, right);
            case EDIT -> clickEdit(player, slot, left, right);
            case CONFIRM -> {
                if (slot == 11 && holder.onYes != null) { click(player); holder.onYes.run(); }
                else if (slot == 15 && holder.onNo != null) { click(player); holder.onNo.run(); }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AdminHolder) event.setCancelled(true);
    }

    private void clickMain(Player player, int slot, boolean left, boolean right) {
        if (slot >= 10 && slot <= 16) {
            String day = CalendarManager.DAYS[slot - 10];
            click(player);
            if (left) openDayMenu(player, day);
            else if (right) startEdit(player, day, -1);
            return;
        }
        switch (slot) {
            case 45 -> {
                plugin.reloadConfig();
                calendar.loadSchedule();
                player.sendMessage(ChatColor.GREEN + "✅ Расписание перечитано из config.yml");
                openMainMenu(player);
            }
            case 47 -> calendar.openCalendar(player);
            case 49 -> player.closeInventory();
            case 53 -> openConfirm(player, "Очистить ВСЁ расписание?", () -> {
                for (String d : CalendarManager.DAYS) calendar.getEvents(d).clear();
                calendar.saveSchedule();
                player.sendMessage(ChatColor.GREEN + "✅ Расписание очищено.");
                openMainMenu(player);
            }, () -> openMainMenu(player));
            default -> { }
        }
    }

    private void clickDay(Player player, String day, int slot, boolean left, boolean right) {
        List<CalendarManager.BossEvent> events = calendar.getEvents(day);
        if (slot >= 0 && slot < events.size() && slot < MAX_EVENTS_PER_DAY) {
            click(player);
            if (left) startEdit(player, day, slot);
            else if (right) {
                CalendarManager.BossEvent e = events.get(slot);
                openConfirm(player, "Удалить событие " + e.time + "?", () -> {
                    if (slot < events.size()) events.remove(slot);
                    calendar.saveSchedule();
                    player.sendMessage(ChatColor.GREEN + "✅ Событие удалено.");
                    openDayMenu(player, day);
                }, () -> openDayMenu(player, day));
            }
            return;
        }
        switch (slot) {
            case 45 -> startEdit(player, day, -1);
            case 46 -> openConfirm(player, "Очистить весь день?", () -> {
                events.clear();
                calendar.saveSchedule();
                player.sendMessage(ChatColor.GREEN + "✅ День очищен.");
                openDayMenu(player, day);
            }, () -> openDayMenu(player, day));
            case 53 -> openMainMenu(player);
            default -> { }
        }
    }

    private void clickEdit(Player player, int slot, boolean left, boolean right) {
        EditSession s = sessions.get(player.getUniqueId());
        if (s == null) { openMainMenu(player); return; }

        // время
        int delta = switch (slot) {
            case 10 -> 60;
            case 11 -> 10;
            case 12 -> 1;
            case 28 -> -60;
            case 29 -> -10;
            case 30 -> -1;
            default -> 0;
        };
        if (delta != 0) {
            s.shift(delta);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, delta > 0 ? 1.4f : 0.9f);
            openEdit(player);
            return;
        }

        // выбор босса
        for (int k = 0; k < BOSS_SLOTS.length; k++) {
            if (slot == BOSS_SLOTS[k]) {
                List<String> ids = bossIds();
                int idx = s.bossPage * BOSS_SLOTS.length + k;
                if (idx < ids.size()) {
                    s.boss = ids.get(idx);
                    click(player);
                    openEdit(player);
                }
                return;
            }
        }

        switch (slot) {
            case 13 -> { s.bossPage--; click(player); openEdit(player); }
            case 31 -> { s.bossPage++; click(player); openEdit(player); }
            case 39 -> {
                if (right) {
                    s.message = CalendarManager.DEFAULT_MESSAGE;
                    click(player);
                    openEdit(player);
                } else {
                    awaitingChat.add(player.getUniqueId());
                    player.closeInventory();
                    player.sendMessage(ChatColor.GOLD + "📝 Напишите в чат новое сообщение для события.");
                    player.sendMessage(ChatColor.GRAY + "Можно &-цвета, {BOSS} и {TIME}. Напишите «отмена», чтобы оставить как было.");
                }
            }
            case 45 -> {
                sessions.remove(player.getUniqueId());
                openDayMenu(player, s.day);
            }
            case 49 -> saveEdit(player, s);
            case 53 -> {
                if (s.index < 0) return;
                List<CalendarManager.BossEvent> events = calendar.getEvents(s.day);
                openConfirm(player, "Удалить это событие?", () -> {
                    if (s.index < events.size()) events.remove(s.index);
                    calendar.saveSchedule();
                    sessions.remove(player.getUniqueId());
                    player.sendMessage(ChatColor.GREEN + "✅ Событие удалено.");
                    openDayMenu(player, s.day);
                }, () -> openEdit(player));
            }
            default -> { }
        }
    }

    // ───────────────────────── Ввод сообщения в чате ─────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!awaitingChat.contains(id)) return;
        event.setCancelled(true);                                  // сообщение не уходит в общий чат
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        Bukkit.getScheduler().runTask(plugin, () -> {
            awaitingChat.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player == null) return;
            EditSession s = sessions.get(id);
            if (s == null) return;

            if (text.isEmpty() || text.equalsIgnoreCase("отмена") || text.equalsIgnoreCase("cancel")) {
                player.sendMessage(ChatColor.GRAY + "Ввод отменён, сообщение не изменено.");
            } else {
                s.message = text;
                player.sendMessage(ChatColor.GREEN + "✅ Сообщение сохранено в черновике: " + GuiUtil.color(text));
                player.sendMessage(ChatColor.GRAY + "Не забудьте нажать «Сохранить».");
            }
            openEdit(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.remove(id);
        awaitingChat.remove(id);
    }

    // ───────────────────────── Вспомогательное ─────────────────────────

    private void click(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.1f);
    }

    private List<String> bossIds() {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("bosses");
        return sec == null ? new ArrayList<>() : new ArrayList<>(sec.getKeys(false));
    }

    private Material bossIcon(String id) {
        String type = plugin.getConfig().getString("bosses." + id + ".boss.type", "").toUpperCase();
        return switch (type) {
            case "ZOMBIE" -> Material.ZOMBIE_HEAD;
            case "SKELETON" -> Material.SKELETON_SKULL;
            case "WITHER_SKELETON" -> Material.WITHER_SKELETON_SKULL;
            case "SPIDER", "CAVE_SPIDER" -> Material.SPIDER_EYE;
            case "ENDER_DRAGON" -> Material.DRAGON_HEAD;
            case "PIGLIN_BRUTE", "PIGLIN" -> Material.PIGLIN_HEAD;
            case "CREEPER" -> Material.CREEPER_HEAD;
            default -> Material.NETHER_STAR;
        };
    }

    private String shorten(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 37) + "..." : s;
    }
}

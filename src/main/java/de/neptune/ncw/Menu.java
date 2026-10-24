package de.neptune.ncw;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.luckperms.api.model.group.Group;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/** Das komplette GUI: Raenge -> Rang -> Befehle / Berechtigungen. */
final class Menu implements InventoryHolder {

    private static final int PAGE_SIZE = 45;

    private final NcwPlugin plugin;
    private final Player viewer;
    private Inventory inv;
    private final Map<Integer, Runnable> handlers = new HashMap<>();

    Menu(NcwPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    void click(int slot) {
        Runnable r = handlers.get(slot);
        if (r != null) r.run();
    }

    // ------------------------------------------------------------ Helfer

    private static Component text(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material m, String name, NamedTextColor color, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(text(name, color));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(text(s, NamedTextColor.GRAY));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private void reset(int size, String title) {
        handlers.clear();
        inv = Bukkit.createInventory(this, size, Component.text(title));
    }

    private void set(int slot, ItemStack it, Runnable onClick) {
        inv.setItem(slot, it);
        if (onClick != null) handlers.put(slot, onClick);
    }

    private boolean stillAllowed(String group) {
        if (plugin.canManage(viewer, group)) return true;
        viewer.sendMessage("§8[§bNCW§8] §cDiesen Rang darfst du nicht verwalten.");
        viewer.closeInventory();
        return false;
    }

    private void show() {
        viewer.openInventory(inv);
    }

    /** Eine Seite mit Eintraegen plus Navigationsleiste. */
    private void paged(String title, List<ItemStack> items, List<Runnable> actions, int page,
                       java.util.function.IntConsumer reopen, Runnable back,
                       ItemStack extra, Runnable extraAction) {
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int p = Math.min(Math.max(page, 0), pages - 1);
        reset(54, title + " §8(" + (p + 1) + "/" + pages + ")");
        int start = p * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < items.size(); i++) {
            set(i, items.get(start + i), actions.get(start + i));
        }
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        for (int i = 45; i < 54; i++) set(i, filler, null);
        if (p > 0) set(45, item(Material.ARROW, "Vorherige Seite", NamedTextColor.YELLOW), () -> reopen.accept(p - 1));
        if (back != null) set(49, item(Material.BARRIER, "Zurueck", NamedTextColor.RED), back);
        if (p < pages - 1) set(53, item(Material.ARROW, "Naechste Seite", NamedTextColor.YELLOW), () -> reopen.accept(p + 1));
        if (extra != null) set(47, extra, extraAction);
        show();
    }

    // ------------------------------------------------------------ Rang-Liste

    void openGroups(int page) {
        List<ItemStack> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (Group g : plugin.sortedGroups()) {
            String name = g.getName();
            if (!plugin.canManage(viewer, name)) continue;
            NcwPlugin.Category cat = plugin.categoryOf(name);
            Material mat = cat == null ? Material.PAPER : cat.icon;
            items.add(item(mat, name, NamedTextColor.AQUA,
                    "Kategorie: " + (cat == null ? "keine" : cat.label),
                    "Freigaben: " + (plugin.hasAll(name) ? "ALLE" : String.valueOf(plugin.allowedOf(name).size())),
                    "Klicken zum Bearbeiten"));
            actions.add(() -> openGroup(name));
        }
        paged("NCW - Raenge", items, actions, page, this::openGroups, null, null, null);
    }

    // ------------------------------------------------------------ Einzelner Rang

    void openGroup(String group) {
        if (!stillAllowed(group)) return;
        reset(27, "NCW - " + group);
        NcwPlugin.Category cat = plugin.categoryOf(group);

        set(10, item(Material.COMMAND_BLOCK, "Befehle", NamedTextColor.GREEN,
                "Befehle aller Plugins", "freigeben oder sperren"), () -> openCommands(group, 0, 0));
        set(12, item(Material.WRITABLE_BOOK, "Berechtigungen", NamedTextColor.GOLD,
                "LuckPerms-Rechte aller Plugins", "setzen oder entfernen"), () -> openPerms(group, 0, 0));

        boolean all = plugin.hasAll(group);
        set(14, item(all ? Material.LIME_CONCRETE : Material.RED_CONCRETE,
                "Alle Befehle: " + (all ? "AN" : "AUS"), all ? NamedTextColor.GREEN : NamedTextColor.RED,
                "Klicken zum Umschalten"), () -> {
            if (!stillAllowed(group)) return;
            plugin.toggleCommand(group, "*");
            openGroup(group);
        });

        boolean admin = plugin.isAdmin(viewer);
        set(16, item(cat == null ? Material.PAPER : cat.icon,
                "Kategorie: " + (cat == null ? "keine" : cat.label), NamedTextColor.LIGHT_PURPLE,
                admin ? "Klicken zum Wechseln" : "Nur Admins koennen das aendern"), admin ? () -> {
            NcwPlugin.Category[] all2 = NcwPlugin.Category.values();
            NcwPlugin.Category next;
            if (cat == null) next = all2[0];
            else if (cat.ordinal() + 1 < all2.length) next = all2[cat.ordinal() + 1];
            else next = null;
            plugin.setCategory(group, next);
            openGroup(group);
        } : null);

        set(22, item(Material.BARRIER, "Zurueck", NamedTextColor.RED), () -> openGroups(0));
        show();
    }

    // ------------------------------------------------------------ Befehle

    private void openCommands(String group, int page, int filterIdx) {
        if (!stillAllowed(group)) return;
        Map<String, String> cmds = new TreeMap<>(plugin.allCommands());
        // eigene Eintraege, die es (noch) nicht als Befehl gibt
        for (String s : plugin.allowedOf(group)) if (!s.equals("*")) cmds.putIfAbsent(s, "Eigener Eintrag");

        List<String> owners = new ArrayList<>(new TreeSet<>(cmds.values()));
        owners.add(0, "Alle Plugins");
        int fi = Math.floorMod(filterIdx, owners.size());
        String filter = owners.get(fi);

        List<ItemStack> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        Set<String> set = plugin.allowedOf(group);
        boolean all = set.contains("*");
        for (Map.Entry<String, String> e : cmds.entrySet()) {
            if (fi != 0 && !e.getValue().equals(filter)) continue;
            String cmd = e.getKey();
            boolean on = all || set.contains(cmd);
            items.add(item(on ? Material.LIME_DYE : Material.GRAY_DYE, "/" + cmd,
                    on ? NamedTextColor.GREEN : NamedTextColor.RED,
                    "Plugin: " + e.getValue(), on ? "Freigegeben" : "Gesperrt", "Klicken zum Umschalten"));
            actions.add(() -> {
                if (!stillAllowed(group)) return;
                plugin.toggleCommand(group, cmd);
                openCommands(group, page, fi);
            });
        }
        ItemStack f = item(Material.HOPPER, "Filter: " + filter, NamedTextColor.YELLOW, "Klicken fuer naechstes Plugin");
        paged("NCW - Befehle " + group, items, actions, page,
                pg -> openCommands(group, pg, fi), () -> openGroup(group),
                f, () -> openCommands(group, 0, fi + 1));
    }

    // ------------------------------------------------------------ Berechtigungen

    private void openPerms(String group, int page, int filterIdx) {
        if (!stillAllowed(group)) return;
        List<String> perms = plugin.allPermissions();
        TreeSet<String> prefixes = new TreeSet<>();
        for (String p : perms) {
            int i = p.indexOf('.');
            prefixes.add(i > 0 ? p.substring(0, i) : p);
        }
        List<String> filters = new ArrayList<>(prefixes);
        filters.add(0, "Alle");
        int fi = Math.floorMod(filterIdx, filters.size());
        String filter = filters.get(fi);

        List<ItemStack> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (String node : perms) {
            if (fi != 0) {
                int i = node.indexOf('.');
                String pre = i > 0 ? node.substring(0, i) : node;
                if (!pre.equals(filter)) continue;
            }
            boolean on = plugin.hasPerm(group, node);
            items.add(item(on ? Material.LIME_DYE : Material.GRAY_DYE, node,
                    on ? NamedTextColor.GREEN : NamedTextColor.RED,
                    on ? "Gesetzt (true)" : "Nicht gesetzt", "Klicken zum Umschalten"));
            actions.add(() -> {
                if (!stillAllowed(group)) return;
                plugin.togglePerm(group, node);
                openPerms(group, page, fi);
            });
        }
        ItemStack f = item(Material.HOPPER, "Filter: " + filter, NamedTextColor.YELLOW, "Klicken fuer naechsten Bereich");
        paged("NCW - Rechte " + group, items, actions, page,
                pg -> openPerms(group, pg, fi), () -> openGroup(group),
                f, () -> openPerms(group, 0, fi + 1));
    }
}

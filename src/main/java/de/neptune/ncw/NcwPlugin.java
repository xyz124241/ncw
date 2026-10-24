package de.neptune.ncw;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Neptune Command Whitelist (NCW)
 * Befehl: /ncw (Aliase: /nwc, /neptunewhitelist)
 */
public final class NcwPlugin extends JavaPlugin implements Listener, TabExecutor {

    public enum Category {
        STAFF("Staff-Mitglieder", Material.DIAMOND),
        BUY("Buy-Ranks", Material.EMERALD),
        OTHER("Sonstige (Media, Friend, ...)", Material.AMETHYST_SHARD);

        public final String label;
        public final Material icon;

        Category(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }

        public static Category parse(String s) {
            if (s == null) return null;
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "staff", "team", "mitglieder" -> STAFF;
                case "buy", "buyranks", "kauf", "shop" -> BUY;
                case "other", "sonstige", "andere", "media" -> OTHER;
                default -> null;
            };
        }
    }

    private static final String PREFIX = "§8[§bNCW§8] §7";

    private LuckPerms lp;
    private File dataFile;

    private boolean enabled = false;
    private String ownerGroup = "";
    private final Map<Category, String> managers = new EnumMap<>(Category.class);
    private final Map<String, Category> categories = new HashMap<>();
    private final Map<String, Set<String>> allowed = new HashMap<>();

    @Override
    public void onEnable() {
        try {
            lp = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            getLogger().severe("LuckPerms nicht gefunden - Plugin wird deaktiviert.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();
        PluginCommand cmd = getCommand("ncw");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NCW aktiv. Whitelist ist " + (enabled ? "AN" : "AUS (erst konfigurieren, dann /ncw enable)"));
    }

    @Override
    public void onDisable() {
        if (dataFile != null) saveData();
    }

    // ------------------------------------------------------------ Daten

    private void loadData() {
        enabled = false;
        ownerGroup = "";
        managers.clear();
        categories.clear();
        allowed.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        enabled = y.getBoolean("enabled", false);
        ownerGroup = y.getString("owner-group", "");
        ConfigurationSection m = y.getConfigurationSection("managers");
        if (m != null) {
            for (String k : m.getKeys(false)) {
                Category c = Category.parse(k);
                String g = m.getString(k, "");
                if (c != null && !g.isEmpty()) managers.put(c, g.toLowerCase(Locale.ROOT));
            }
        }
        ConfigurationSection c = y.getConfigurationSection("categories");
        if (c != null) {
            for (String g : c.getKeys(false)) {
                Category cat = Category.parse(c.getString(g));
                if (cat != null) categories.put(g.toLowerCase(Locale.ROOT), cat);
            }
        }
        ConfigurationSection a = y.getConfigurationSection("allowed");
        if (a != null) {
            for (String g : a.getKeys(false)) {
                allowed.put(g.toLowerCase(Locale.ROOT), new TreeSet<>(a.getStringList(g)));
            }
        }
    }

    void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("enabled", enabled);
        y.set("owner-group", ownerGroup);
        for (Map.Entry<Category, String> e : managers.entrySet()) y.set("managers." + e.getKey().name(), e.getValue());
        for (Map.Entry<String, Category> e : categories.entrySet()) y.set("categories." + e.getKey(), e.getValue().name());
        for (Map.Entry<String, Set<String>> e : allowed.entrySet()) y.set("allowed." + e.getKey(), new ArrayList<>(e.getValue()));
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Konnte data.yml nicht speichern: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ Zugriff / Rechte

    LuckPerms lp() {
        return lp;
    }

    Set<String> groupsOf(Player p) {
        User u = lp.getUserManager().getUser(p.getUniqueId());
        if (u == null) return Collections.emptySet();
        Set<String> s = new HashSet<>();
        for (Group g : u.getInheritedGroups(QueryOptions.nonContextual())) s.add(g.getName().toLowerCase(Locale.ROOT));
        s.add(u.getPrimaryGroup().toLowerCase(Locale.ROOT));
        return s;
    }

    /** Voller Zugriff: Konsole, ncw.admin oder Mitglied der Owner-Gruppe. */
    boolean isAdmin(CommandSender s) {
        if (!(s instanceof Player p)) return true;
        if (p.hasPermission("ncw.admin")) return true;
        return !ownerGroup.isEmpty() && groupsOf(p).contains(ownerGroup);
    }

    /** Darf der Sender die Einstellungen dieser Gruppe aendern? */
    boolean canManage(CommandSender s, String group) {
        if (isAdmin(s)) return true;
        if (!(s instanceof Player p)) return false;
        Category cat = categories.get(group.toLowerCase(Locale.ROOT));
        if (cat == null) return false;
        String mgr = managers.get(cat);
        return mgr != null && groupsOf(p).contains(mgr);
    }

    boolean canUse(CommandSender s) {
        if (isAdmin(s)) return true;
        if (!(s instanceof Player p)) return false;
        Set<String> g = groupsOf(p);
        for (String m : managers.values()) if (g.contains(m)) return true;
        return false;
    }

    Category categoryOf(String group) {
        return categories.get(group.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------ Whitelist

    Set<String> allowedOf(String group) {
        return allowed.computeIfAbsent(group.toLowerCase(Locale.ROOT), k -> new TreeSet<>());
    }

    boolean hasAll(String group) {
        return allowedOf(group).contains("*");
    }

    boolean toggleCommand(String group, String command) {
        Set<String> s = allowedOf(group);
        String c = command.toLowerCase(Locale.ROOT);
        boolean now;
        if (s.contains(c)) {
            s.remove(c);
            now = false;
        } else {
            s.add(c);
            now = true;
        }
        saveData();
        return now;
    }

    void setCategory(String group, Category c) {
        if (c == null) categories.remove(group.toLowerCase(Locale.ROOT));
        else categories.put(group.toLowerCase(Locale.ROOT), c);
        saveData();
    }

    private Set<String> candidates(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        Set<String> c = new HashSet<>();
        c.add(l);
        int i = l.indexOf(':');
        String base = i >= 0 ? l.substring(i + 1) : l;
        c.add(base);
        Command cmd = Bukkit.getCommandMap().getCommand(l);
        if (cmd == null) cmd = Bukkit.getCommandMap().getCommand(base);
        if (cmd != null) {
            c.add(cmd.getName().toLowerCase(Locale.ROOT));
            for (String a : cmd.getAliases()) c.add(a.toLowerCase(Locale.ROOT));
        }
        return c;
    }

    boolean isAllowed(Player p, String label) {
        if (!enabled || isAdmin(p)) return true;
        Set<String> cand = candidates(label);
        if (cand.contains("ncw")) return true;
        for (String g : groupsOf(p)) {
            Set<String> s = allowed.get(g);
            if (s == null) continue;
            if (s.contains("*")) return true;
            for (String c : cand) if (s.contains(c)) return true;
        }
        return false;
    }

    /** Alle Befehle aller Plugins (Name -> Plugin). */
    Map<String, String> allCommands() {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Command> e : Bukkit.getCommandMap().getKnownCommands().entrySet()) {
            String name = e.getKey();
            if (name.contains(":")) continue;
            Command c = e.getValue();
            String owner = c instanceof PluginCommand pc ? pc.getPlugin().getName() : "Minecraft/Server";
            out.put(name.toLowerCase(Locale.ROOT), owner);
        }
        return out;
    }

    /** Alle registrierten Berechtigungen aller Plugins. */
    List<String> allPermissions() {
        return Bukkit.getPluginManager().getPermissions().stream()
                .map(Permission::getName)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct().sorted().collect(Collectors.toList());
    }

    // ------------------------------------------------------------ LuckPerms-Permissions

    boolean hasPerm(String group, String node) {
        Group g = lp.getGroupManager().getGroup(group);
        if (g == null) return false;
        return g.getNodes(NodeType.PERMISSION).stream()
                .anyMatch(n -> n.getKey().equalsIgnoreCase(node) && n.getValue() && !n.hasExpiry());
    }

    void setPerm(String group, String node, Boolean value) {
        Group g = lp.getGroupManager().getGroup(group);
        if (g == null) return;
        g.data().clear(NodeType.PERMISSION.predicate(n -> n.getKey().equalsIgnoreCase(node) && n.getContexts().isEmpty()));
        if (value != null) g.data().add(Node.builder(node).value(value).build());
        lp.getGroupManager().saveGroup(g);
    }

    void togglePerm(String group, String node) {
        setPerm(group, node, hasPerm(group, node) ? null : Boolean.TRUE);
    }

    // ------------------------------------------------------------ Events

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        String label = msg.startsWith("/") ? msg.substring(1) : msg;
        int sp = label.indexOf(' ');
        if (sp >= 0) label = label.substring(0, sp);
        if (label.isEmpty()) return;
        if (!isAllowed(e.getPlayer(), label)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(PREFIX + "§cDiesen Befehl darfst du nicht benutzen.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSend(PlayerCommandSendEvent e) {
        if (!enabled || isAdmin(e.getPlayer())) return;
        e.getCommands().removeIf(name -> !isAllowed(e.getPlayer(), name));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        menu.click(e.getRawSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ------------------------------------------------------------ Befehl

    private void msg(CommandSender s, String m) {
        s.sendMessage(PREFIX + m);
    }

    private boolean groupExists(CommandSender s, String name) {
        if (lp.getGroupManager().getGroup(name) != null) return true;
        msg(s, "§cDie LuckPerms-Gruppe §f" + name + " §cgibt es nicht.");
        return false;
    }

    private void help(CommandSender s) {
        msg(s, "§bNeptune Command Whitelist");
        s.sendMessage("§f/ncw gui §7- Menue oeffnen");
        s.sendMessage("§f/ncw list §7- Raenge und Kategorien");
        s.sendMessage("§f/ncw info <rang> §7- Freigaben eines Rangs");
        s.sendMessage("§f/ncw allow <rang> <befehl|*> §7- Befehl freigeben");
        s.sendMessage("§f/ncw deny <rang> <befehl|*> §7- Befehl sperren");
        s.sendMessage("§f/ncw perm <rang> <permission> <true|false|unset> §7- LuckPerms-Recht setzen");
        if (isAdmin(s)) {
            s.sendMessage("§f/ncw category <rang> <staff|buy|other|none> §7- Kategorie");
            s.sendMessage("§f/ncw manager <staff|buy|other> <rang|none> §7- Oberster Rang der Kategorie");
            s.sendMessage("§f/ncw owner <rang|none> §7- Rang, der alles darf");
            s.sendMessage("§f/ncw enable|disable §7- Whitelist an/aus");
            s.sendMessage("§f/ncw reload §7- Daten neu laden");
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!canUse(s)) {
            msg(s, "§cDazu hast du keine Berechtigung.");
            return true;
        }
        String sub = a.length == 0 ? "gui" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(s);
            case "gui", "menu" -> {
                if (s instanceof Player p) new Menu(this, p).openGroups(0);
                else msg(s, "§cDas Menue gibt es nur im Spiel.");
            }
            case "list" -> {
                msg(s, "§7Raenge:");
                for (Group g : sortedGroups()) {
                    if (!canManage(s, g.getName())) continue;
                    Category cat = categoryOf(g.getName());
                    s.sendMessage(" §f" + g.getName() + " §8| §7" + (cat == null ? "keine Kategorie" : cat.label)
                            + " §8| §7" + allowedOf(g.getName()).size() + " Eintraege");
                }
                msg(s, "§7Whitelist: " + (enabled ? "§aAN" : "§cAUS")
                        + " §8| §7Owner: §f" + (ownerGroup.isEmpty() ? "-" : ownerGroup));
                for (Category cat : Category.values())
                    s.sendMessage(" §7" + cat.label + ": §f" + managers.getOrDefault(cat, "-"));
            }
            case "info" -> {
                if (a.length < 2 || !groupExists(s, a[1])) return true;
                if (!canManage(s, a[1])) {
                    msg(s, "§cDiesen Rang darfst du nicht verwalten.");
                    return true;
                }
                Category cat = categoryOf(a[1]);
                msg(s, "§f" + a[1] + " §8| §7" + (cat == null ? "keine Kategorie" : cat.label));
                Set<String> set = allowedOf(a[1]);
                s.sendMessage(" §7Befehle: §f" + (set.isEmpty() ? "-" : String.join(", ", set)));
            }
            case "allow", "deny" -> {
                if (a.length < 3 || !groupExists(s, a[1])) {
                    msg(s, "§7Nutzung: /ncw " + sub + " <rang> <befehl|*>");
                    return true;
                }
                if (!canManage(s, a[1])) {
                    msg(s, "§cDiesen Rang darfst du nicht verwalten.");
                    return true;
                }
                String cmdName = a[2].toLowerCase(Locale.ROOT);
                if (cmdName.startsWith("/")) cmdName = cmdName.substring(1);
                Set<String> set = allowedOf(a[1]);
                if (sub.equals("allow")) set.add(cmdName);
                else set.remove(cmdName);
                saveData();
                msg(s, "§f/" + cmdName + " §7fuer §f" + a[1] + (sub.equals("allow") ? " §afreigegeben" : " §cgesperrt"));
            }
            case "perm" -> {
                if (a.length < 4 || !groupExists(s, a[1])) {
                    msg(s, "§7Nutzung: /ncw perm <rang> <permission> <true|false|unset>");
                    return true;
                }
                if (!canManage(s, a[1])) {
                    msg(s, "§cDiesen Rang darfst du nicht verwalten.");
                    return true;
                }
                Boolean v = switch (a[3].toLowerCase(Locale.ROOT)) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    case "unset" -> null;
                    default -> {
                        msg(s, "§cWert muss true, false oder unset sein.");
                        yield Boolean.FALSE;
                    }
                };
                if (!a[3].equalsIgnoreCase("true") && !a[3].equalsIgnoreCase("false") && !a[3].equalsIgnoreCase("unset"))
                    return true;
                setPerm(a[1], a[2], v);
                msg(s, "§7Recht §f" + a[2] + " §7fuer §f" + a[1] + " §7-> §f" + (v == null ? "entfernt" : v));
            }
            case "category" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen Kategorien aendern.");
                    return true;
                }
                if (a.length < 3 || !groupExists(s, a[1])) {
                    msg(s, "§7Nutzung: /ncw category <rang> <staff|buy|other|none>");
                    return true;
                }
                if (a[2].equalsIgnoreCase("none")) {
                    setCategory(a[1], null);
                    msg(s, "§f" + a[1] + " §7hat jetzt keine Kategorie.");
                } else {
                    Category cat = Category.parse(a[2]);
                    if (cat == null) {
                        msg(s, "§cKategorie: staff, buy, other oder none.");
                        return true;
                    }
                    setCategory(a[1], cat);
                    msg(s, "§f" + a[1] + " §7ist jetzt in §f" + cat.label);
                }
            }
            case "manager" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                if (a.length < 3) {
                    msg(s, "§7Nutzung: /ncw manager <staff|buy|other> <rang|none>");
                    return true;
                }
                Category cat = Category.parse(a[1]);
                if (cat == null) {
                    msg(s, "§cKategorie: staff, buy oder other.");
                    return true;
                }
                if (a[2].equalsIgnoreCase("none")) managers.remove(cat);
                else {
                    if (!groupExists(s, a[2])) return true;
                    managers.put(cat, a[2].toLowerCase(Locale.ROOT));
                }
                saveData();
                msg(s, "§7Oberster Rang fuer §f" + cat.label + "§7: §f" + managers.getOrDefault(cat, "-"));
            }
            case "owner" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                if (a.length < 2) {
                    msg(s, "§7Nutzung: /ncw owner <rang|none>");
                    return true;
                }
                if (a[1].equalsIgnoreCase("none")) ownerGroup = "";
                else {
                    if (!groupExists(s, a[1])) return true;
                    ownerGroup = a[1].toLowerCase(Locale.ROOT);
                }
                saveData();
                msg(s, "§7Owner-Rang: §f" + (ownerGroup.isEmpty() ? "-" : ownerGroup));
            }
            case "enable", "disable" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                enabled = sub.equals("enable");
                saveData();
                msg(s, "§7Whitelist ist jetzt " + (enabled ? "§aAN" : "§cAUS"));
                if (enabled) msg(s, "§7Tipp: Gib dem Rang 'default' z.B. /spawn, /msg usw., sonst kann niemand etwas tippen.");
            }
            case "reload" -> {
                if (!isAdmin(s)) {
                    msg(s, "§cNur Admins duerfen das aendern.");
                    return true;
                }
                loadData();
                msg(s, "§7Daten neu geladen.");
            }
            default -> help(s);
        }
        return true;
    }

    List<Group> sortedGroups() {
        List<Group> list = new ArrayList<>(lp.getGroupManager().getLoadedGroups());
        list.sort(Comparator.<Group>comparingInt(g -> g.getWeight().orElse(0)).reversed()
                .thenComparing(Group::getName));
        return list;
    }

    // ------------------------------------------------------------ Tab-Completion

    private List<String> filter(Collection<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).limit(80).collect(Collectors.toList());
    }

    private List<String> groupNames() {
        return lp.getGroupManager().getLoadedGroups().stream().map(Group::getName).sorted().collect(Collectors.toList());
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (!canUse(s)) return Collections.emptyList();
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("gui", "help", "list", "info", "allow", "deny", "perm"));
            if (isAdmin(s)) subs.addAll(List.of("category", "manager", "owner", "enable", "disable", "reload"));
            return filter(subs, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            return switch (sub) {
                case "info", "allow", "deny", "perm", "category" -> filter(groupNames(), a[1]);
                case "manager" -> filter(List.of("staff", "buy", "other"), a[1]);
                case "owner" -> {
                    List<String> o = new ArrayList<>(groupNames());
                    o.add("none");
                    yield filter(o, a[1]);
                }
                default -> Collections.emptyList();
            };
        }
        if (a.length == 3) {
            switch (sub) {
                case "allow" -> {
                    List<String> o = new ArrayList<>(allCommands().keySet());
                    o.add("*");
                    return filter(o, a[2]);
                }
                case "deny" -> {
                    return filter(allowedOf(a[1]), a[2]);
                }
                case "perm" -> {
                    return filter(allPermissions(), a[2]);
                }
                case "category" -> {
                    return filter(List.of("staff", "buy", "other", "none"), a[2]);
                }
                case "manager" -> {
                    List<String> o = new ArrayList<>(groupNames());
                    o.add("none");
                    return filter(o, a[2]);
                }
                default -> {
                    return Collections.emptyList();
                }
            }
        }
        if (a.length == 4 && sub.equals("perm")) return filter(List.of("true", "false", "unset"), a[3]);
        return Collections.emptyList();
    }
}

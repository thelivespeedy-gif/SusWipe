package com.example.suswipe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

import java.io.File;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SusWipePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, Wipe> wipes = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, UUID> inspecting = new HashMap<>();
    private final Map<UUID, Long> refreshAt = new HashMap<>();
    private final Map<UUID, String> searchWaiting = new HashMap<>();
    private File dataFile;
    private YamlConfiguration data;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    private record Wipe(UUID uuid, String name, String reason, String issuerId, String issuer,
                        long created, long expires, int days, String id) {}
    private record Session(Location location, GameMode mode, boolean allowFlight, boolean flying, long started) {}
    private record Pending(UUID target, String name, int days, String reason, String issuer, UUID issuerId) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "wipes.yml");
        loadWipes();
        Objects.requireNonNull(getCommand("sus")).setExecutor(this);
        Objects.requireNonNull(getCommand("sus")).setTabCompleter(this);
        for (String cmd : List.of("wipe", "unwipe", "wipelist", "suswipe")) {
            Objects.requireNonNull(getCommand(cmd)).setExecutor(this);
            Objects.requireNonNull(getCommand(cmd)).setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        expireWipes();
        getLogger().info("SusWipe enabled. Loaded " + wipes.size() + " wipe record(s).");
    }
    @Override public void onDisable() {
        saveWipes();
        getLogger().info("SusWipe disabled.");
    }

    private void loadWipes() {
        if (!dataFile.exists()) {
            try { getDataFolder().mkdirs(); dataFile.createNewFile(); } catch (IOException e) { getLogger().severe("Could not create wipes.yml: " + e.getMessage()); }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        wipes.clear();
        ConfigurationSection sec = data.getConfigurationSection("wipes");
        if (sec != null) for (String key : sec.getKeys(false)) {
            try {
                ConfigurationSection s = sec.getConfigurationSection(key);
                if (s == null) continue;
                Wipe w = new Wipe(UUID.fromString(key), s.getString("name", "Unknown"), s.getString("reason", "Rule violation"),
                    s.getString("issuer-id", "console"), s.getString("issuer", "Console"), s.getLong("created"),
                    s.getLong("expires"), s.getInt("days", 1), s.getString("id", UUID.randomUUID().toString()));
                if (w.expires() > System.currentTimeMillis()) wipes.put(w.uuid(), w);
                else getLogger().info("Expired SusWipe record ignored for " + w.name());
            } catch (Exception ex) { getLogger().warning("Skipping malformed wipe record " + key); }
        }
        saveWipes();
    }
    private void saveWipes() {
        if (data == null) data = new YamlConfiguration();
        data.set("wipes", null);
        for (Wipe w : wipes.values()) {
            String p = "wipes." + w.uuid();
            data.set(p + ".name", w.name()); data.set(p + ".reason", w.reason());
            data.set(p + ".issuer-id", w.issuerId()); data.set(p + ".issuer", w.issuer());
            data.set(p + ".created", w.created()); data.set(p + ".expires", w.expires());
            data.set(p + ".days", w.days()); data.set(p + ".id", w.id());
        }
        try {
            if (getConfig().getBoolean("storage.backup-before-save", true) && dataFile.exists() && dataFile.length() > 0) {
                File backup = new File(getDataFolder(), "wipes.yml.bak");
                java.nio.file.Files.copy(dataFile.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            data.save(dataFile);
        } catch (IOException e) { getLogger().severe("Failed to save wipe data: " + e.getMessage()); }
    }
    private void audit(String action, String actor, UUID target, String details) {
        if (getConfig().getBoolean("logging.audit", true))
            getLogger().info("[AUDIT] action=" + action + " actor=" + actor + " target=" + target + " details=" + details + " timestamp=" + Instant.now());
    }
    private boolean allowed(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        s.sendMessage(color("§cYou do not have permission: §f" + perm)); return false;
    }
    private String color(String s) { return s == null ? "" : s.replace('&', '§'); }
    private ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat); ItemMeta m = it.getItemMeta();
        m.displayName(legacy.deserialize(color(name)));
        if (lore.length > 0) m.lore(Arrays.stream(lore).map(x -> legacy.deserialize(color(x))).toList());
        it.setItemMeta(m); return it;
    }
    private ItemStack head(UUID uuid, String name, String... lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) meta.setOwningPlayer(online);
        else if (uuid != null) {
            try { meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid)); } catch (Exception ignored) {}
        }
        meta.displayName(legacy.deserialize(color(name)));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(x -> legacy.deserialize(color(x))).toList());
        it.setItemMeta(meta); return it;
    }
    private void border(Inventory inv) {
        Material border = Material.matchMaterial(getConfig().getString("gui.border", "GRAY_STAINED_GLASS_PANE"));
        if (border == null || !border.isItem()) border = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack pane = item(border, "§7 ");
        for (int i=0;i<9;i++) inv.setItem(i, pane);
        for (int i=45;i<54;i++) inv.setItem(i, pane);
        if (getConfig().getBoolean("gui.black-corners", true)) {
            ItemStack black = item(Material.BLACK_STAINED_GLASS_PANE, "§8 ");
            for (int i : new int[]{0,8,45,53}) inv.setItem(i, black);
        }
    }
    private void openScanner(Player p, int page) {
        Inventory inv = Bukkit.createInventory(p, 54, legacy.deserialize(color(getConfig().getString("gui.scanner-title", "§8§lSUS §7» §fPlayer Scanner"))));
        border(inv);
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int max = Math.max(1, Math.min(36, getConfig().getInt("gui.max-profiles", 36)));
        int start = Math.max(0, page) * max;
        for (int i=0; i<max && start+i<online.size(); i++) {
            Player t = online.get(start+i);
            List<String> lore = new ArrayList<>();
            lore.add("§7Status: §aONLINE"); lore.add("§7World: §f" + t.getWorld().getName());
            if (getConfig().getBoolean("gui.show-health-ping", true)) {
                lore.add("§7Health: §c" + Math.ceil(t.getHealth()) + "/" + t.getMaxHealth());
                try { lore.add("§7Ping: §f" + t.getPing() + " ms"); } catch (Throwable ignored) {}
            }
            lore.add("§eClick to inspect this player");
            inv.setItem(9+i, head(t.getUniqueId(), "§a§l" + t.getName(), lore.toArray(String[]::new)));
        }
        inv.setItem(45, item(Material.ARROW, "§e§lPrevious Page", "§7Return to the previous page."));
        inv.setItem(46, item(Material.CLOCK, "§b§lRefresh Players", "§7Update the online player list."));
        inv.setItem(47, item(Material.NETHER_STAR, "§a§lOnline Players", "§7Currently online: §f" + online.size()));
        inv.setItem(48, item(Material.SPYGLASS, "§d§lSearch Player", "§7Find a player by username.", "§eClick, then type in chat."));
        inv.setItem(49, item(Material.REDSTONE_BLOCK, "§c§lActive Wipes", "§7View players banned by SusWipe."));
        inv.setItem(50, item(Material.BOOK, "§b§lScanner Information", "§7Online players: §f" + online.size(), "§7Offline profiles: §f0", "§7Offline random profiles are disabled."));
        inv.setItem(51, item(Material.COMPARATOR, "§6§lSettings", "§7Settings are configured in config.yml."));
        inv.setItem(52, item(Material.BARRIER, "§c§lClose", "§7Close the scanner."));
        inv.setItem(53, item(Material.ARROW, "§e§lNext Page", "§7View the next page."));
        p.openInventory(inv);
    }
    private void openInspect(Player viewer, Player target, int page) {
        Inventory inv = Bukkit.createInventory(viewer, 27, legacy.deserialize(color("§8§lINSPECT §7» §f" + target.getName())));
        inv.setItem(13, head(target.getUniqueId(), "§a§l" + target.getName(), "§7UUID: §f" + target.getUniqueId()));
        inv.setItem(10, item(Material.ENDER_PEARL, "§c§lTeleport in Spectator", "§7Teleport to the selected player.", "§7Switch to spectator mode.", "§eClick to continue."));
        inv.setItem(12, item(Material.BOOK, "§b§lPlayer Information", "§7Username: §f" + target.getName(), "§7UUID: §f" + target.getUniqueId(), "§7World: §f" + target.getWorld().getName(), "§7Health: §c" + Math.ceil(target.getHealth()) + "/" + target.getMaxHealth(), "§7Game mode: §f" + target.getGameMode(), "§7Ping: §f" + target.getPing() + " ms"));
        inv.setItem(14, item(Material.RED_CONCRETE, "§4§lWipe Player", "§7Open the timed-ban menu.", "§cRequires wipe permission."));
        inv.setItem(16, item(Material.ARROW, "§e§lBack", "§7Return to scanner."));
        inv.setItem(22, item(Material.BARRIER, "§c§lClose"));
        viewer.openInventory(inv);
        inspecting.put(viewer.getUniqueId(), target.getUniqueId());
    }
    private void openConfirm(Player p, Pending action) {
        pending.put(p.getUniqueId(), action);
        Inventory inv = Bukkit.createInventory(p, 27, legacy.deserialize(color("§8§lCONFIRM WIPE")));
        inv.setItem(11, head(action.target(), "§c§l" + action.name(), "§7Duration: §f" + action.days() + " day(s)", "§7Reason: §f" + action.reason(), "§7Expires: §f" + fmt(action.expires())));
        inv.setItem(13, item(Material.LIME_CONCRETE, "§a§lCONFIRM WIPE", "§7Apply the timed ban."));
        inv.setItem(15, item(Material.RED_CONCRETE, "§c§lCANCEL", "§7Do not apply the wipe."));
        p.openInventory(inv);
    }
    private long expires(Pending p) { return System.currentTimeMillis() + p.days() * 86400000L; }
    private String fmt(long millis) {
        try {
            ZoneId zone = ZoneId.systemDefault();
            String z = getConfig().getString("wipe.timezone", "server");
            if (!z.equalsIgnoreCase("server")) zone = ZoneId.of(z);
            return DateTimeFormatter.ofPattern(getConfig().getString("wipe.date-format", "yyyy-MM-dd HH:mm:ss z")).withZone(zone).format(Instant.ofEpochMilli(millis));
        } catch (Exception e) { return Instant.ofEpochMilli(millis).toString(); }
    }
    private void applyWipe(CommandSender actor, Pending a) {
        if (actor instanceof Player pl && !pl.hasPermission("suswipe.wipe")) { pl.sendMessage(color("§cPermission denied.")); return; }
        if (isProtected(a.target(), a.name()) && !(actor.hasPermission("suswipe.bypass.protection"))) {
            actor.sendMessage(color("§cThat player is protected from staff actions.")); audit("WIPE_DENIED_PROTECTED", actor.getName(), a.target(), a.name()); return;
        }
        Wipe old = wipes.get(a.target());
        if (old != null) { actor.sendMessage(color("§cThis player already has a SusWipe. Use /wipe again only after removing it with /unwipe.")); return; }
        long now = System.currentTimeMillis(), end = now + a.days() * 86400000L;
        Wipe w = new Wipe(a.target(), a.name(), cleanReason(a.reason()), a.issuerId() == null ? "console" : a.issuerId().toString(), a.issuer(), now, end, a.days(), UUID.randomUUID().toString());
        wipes.put(w.uuid(), w); saveWipes();
        Wipe persisted = wipes.get(w.uuid());
        if (persisted == null) { actor.sendMessage(color("§cCould not save wipe record.")); return; }
        Player target = Bukkit.getPlayer(w.uuid());
        if (target != null) {
            if (target.getHealth() > 0) target.setHealth(0);
            target.kick(legacy.deserialize(banMessage(w)));
        }
        actor.sendMessage(color("§aSusWipe applied to §f" + w.name() + " §afor §f" + w.days() + " day(s)."));
        audit("WIPE", actor.getName(), w.uuid(), "reason=" + w.reason() + " expires=" + fmt(w.expires()));
    }
    private String banMessage(Wipe w) {
        return "§c§lYOU HAVE BEEN WIPED\n§7Your access to this server has been suspended.\n\n§cReason: §f" + w.reason()
            + "\n§cDuration: §f" + w.days() + " day(s)\n§cIssued by: §f" + w.issuer() + "\n§cExpires: §f" + fmt(w.expires())
            + "\n\n§7If you believe this is a mistake, contact the server staff.";
    }
    private String cleanReason(String r) {
        String s = (r == null || r.isBlank()) ? getConfig().getString("wipe.default-reason", "Rule violation") : r;
        return s.replaceAll("[§&][0-9a-fk-orxA-FK-ORX]", "").replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(120, s.replaceAll("[§&][0-9a-fk-orxA-FK-ORX]", "").replaceAll("[\\r\\n\\t]", " ").length()));
    }
    private boolean isProtected(UUID id, String name) {
        if (!getConfig().getBoolean("staff-protection", true)) return false;
        for (String s : getConfig().getStringList("wipe.protected-players"))
            if (s.equalsIgnoreCase(name) || Bukkit.getOfflinePlayer(s).getUniqueId().equals(id)) return true;
        return false;
    }
    private void expireWipes() {
        long now = System.currentTimeMillis(); boolean changed = wipes.values().removeIf(w -> w.expires() <= now);
        if (changed) saveWipes();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long t = System.currentTimeMillis();
            List<Wipe> expired = wipes.values().stream().filter(w -> w.expires() <= t).toList();
            for (Wipe w : expired) { wipes.remove(w.uuid()); audit("EXPIRE", "system", w.uuid(), w.name()); }
            if (!expired.isEmpty()) saveWipes();
        }, 1200L, 1200L);
    }
    private void openWipes(Player p, int page) {
        Inventory inv = Bukkit.createInventory(p, 54, legacy.deserialize(color(getConfig().getString("gui.wipes-title", "§8§lACTIVE §c§lWIPES"))));
        border(inv);
        List<Wipe> list = wipes.values().stream().filter(w -> w.expires() > System.currentTimeMillis()).sorted(Comparator.comparing(Wipe::expires)).toList();
        if (list.isEmpty()) inv.setItem(22, item(Material.BARRIER, "§7There are currently no active wipes."));
        int start = Math.max(0,page)*36;
        for (int i=0;i<36 && start+i<list.size();i++) {
            Wipe w = list.get(start+i); boolean online = Bukkit.getPlayer(w.uuid()) != null;
            inv.setItem(9+i, head(w.uuid(), (online ? "§c" : "§7") + w.name(), "§7Reason: §f" + w.reason(), "§7Issuer: §f" + w.issuer(), "§7Duration: §f" + w.days() + " days", "§7Expires: §f" + fmt(w.expires()), "§7Remaining: §f" + remaining(w.expires()), "§7Status: " + (online ? "§aONLINE" : "§8OFFLINE")));
        }
        inv.setItem(45, item(Material.ARROW,"§ePrevious Page"));
        inv.setItem(49, item(Material.ARROW,"§eBack to Scanner"));
        inv.setItem(53, item(Material.ARROW,"§eNext Page"));
        p.openInventory(inv);
    }
    private String remaining(long end) {
        long s = Math.max(0,(end-System.currentTimeMillis())/1000);
        long d=s/86400; s%=86400; long h=s/3600; s%=3600; long m=s/60;
        return d+"d "+h+"h "+m+"m";
    }
    private void returnSession(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) { p.sendMessage(color("§cYou do not have an active SusWipe inspection session.")); return; }
        Location loc = s.location();
        if (loc.getWorld() == null) { loc = Bukkit.getWorlds().get(0).getSpawnLocation(); p.sendMessage(color("§eOriginal world unavailable; returned to server spawn.")); }
        p.teleport(loc); p.setGameMode(s.mode());
        p.setAllowFlight(s.allowFlight());
        if (s.allowFlight()) p.setFlying(s.flying());
        inspecting.remove(p.getUniqueId());
        p.sendMessage(color("§aReturned from inspection."));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String c = command.getName().toLowerCase(Locale.ROOT);
        switch (c) {
            case "sus" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("return")) {
                    if (!allowed(sender,"suswipe.teleport")) return true;
                    if (sender instanceof Player p) returnSession(p); else sender.sendMessage("Only players can return from inspection.");
                } else if (args.length > 0 && args[0].equalsIgnoreCase("refresh")) {
                    if (!allowed(sender,"suswipe.sus")) return true;
                    if (sender instanceof Player p) openScanner(p,0); else sender.sendMessage("Only players can open the scanner.");
                } else {
                    if (!allowed(sender,"suswipe.sus")) return true;
                    if (sender instanceof Player p) openScanner(p,0); else sender.sendMessage("Only players can open the scanner.");
                } return true;
            }
            case "wipe" -> {
                if (!allowed(sender,"suswipe.wipe")) return true;
                if (args.length < 2) { sender.sendMessage(color("§cUsage: /wipe <player> <days> [reason]")); return true; }
                int days;
                try { days = Integer.parseInt(args[1]); } catch (NumberFormatException e) { sender.sendMessage(color("§cDays must be a positive whole number.")); return true; }
                if (days < 1 || days > getConfig().getInt("wipe.max-days",365)) { sender.sendMessage(color("§cDays must be between 1 and " + getConfig().getInt("wipe.max-days",365) + ".")); return true; }
                Player online = Bukkit.getPlayerExact(args[0]);
                OfflinePlayer off = online != null ? online : Bukkit.getOfflinePlayerIfCached(args[0]);
                if (off == null || (!off.hasPlayedBefore() && online == null)) { sender.sendMessage(color("§cThat player has not joined before; their UUID cannot be safely resolved.")); return true; }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args,2,args.length)) : getConfig().getString("wipe.default-reason","Rule violation");
                Pending a = new Pending(off.getUniqueId(), online != null ? online.getName() : off.getName(), days, cleanReason(reason), sender.getName(), sender instanceof Player p ? p.getUniqueId() : null);
                if (sender instanceof Player p) openConfirm(p,a); else applyWipe(sender,a);
                return true;
            }
            case "unwipe" -> {
                if (!allowed(sender,"suswipe.unwipe")) return true;
                if (args.length != 1) { sender.sendMessage(color("§cUsage: /unwipe <player>")); return true; }
                OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(args[0]);
                if (off == null) { sender.sendMessage(color("§cCould not resolve that player's known profile.")); return true; }
                Wipe w = wipes.remove(off.getUniqueId());
                if (w == null) { sender.sendMessage(color("§cThis player does not have an active SusWipe ban.")); return true; }
                saveWipes(); sender.sendMessage(color("§aRemoved SusWipe from §f" + w.name() + "§a."));
                Player online = Bukkit.getPlayer(w.uuid()); if (online != null) online.sendMessage(color("§aYour SusWipe ban has been removed."));
                audit("UNWIPE",sender.getName(),w.uuid(),"removed record "+w.id()); return true;
            }
            case "wipelist" -> {
                if (!allowed(sender,"suswipe.wipelist")) return true;
                if (sender instanceof Player p) openWipes(p,0); else sender.sendMessage("Only players can open the wipes GUI.");
                return true;
            }
            case "suswipe" -> {
                if (!allowed(sender,"suswipe.reload")) return true;
                if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) { sender.sendMessage(color("§cUsage: /suswipe reload")); return true; }
                reloadConfig(); loadWipes(); sender.sendMessage(color("§aSusWipe configuration and data reloaded.")); audit("RELOAD",sender.getName(),null,"config reload"); return true;
            }
        }
        return false;
    }
    @Override public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] args) {
        String c=cmd.getName().toLowerCase(Locale.ROOT);
        if (c.equals("sus") && args.length==1) return List.of("refresh","return").stream().filter(x->x.startsWith(args[0].toLowerCase())).toList();
        if (c.equals("suswipe") && args.length==1) return List.of("reload");
        if (c.equals("wipe") && args.length==1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n->n.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        if (c.equals("wipe") && args.length==2) return List.of("1","3","7","14","30");
        if (c.equals("unwipe") && args.length==1) return wipes.values().stream().map(Wipe::name).filter(n->n.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        return List.of();
    }
    @EventHandler public void onLogin(PlayerLoginEvent e) {
        Wipe w=wipes.get(e.getPlayer().getUniqueId());
        if (w == null) return;
        if (w.expires() <= System.currentTimeMillis()) { wipes.remove(w.uuid()); saveWipes(); return; }
        e.disallow(PlayerLoginEvent.Result.KICK_BANNED, legacy.deserialize(banMessage(w)));
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Session s=sessions.get(e.getPlayer().getUniqueId());
        if (s != null && getConfig().getBoolean("inspection.restore-on-join",true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player p=e.getPlayer(); if (!p.isOnline()) return;
                p.setGameMode(s.mode()); p.setAllowFlight(s.allowFlight());
                if (s.allowFlight()) p.setFlying(s.flying());
                p.sendMessage(color("§eYour inspection game mode was restored. Use /sus return if you also want to return to your saved location."));
            }, 20L);
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { searchWaiting.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        UUID id=e.getPlayer().getUniqueId();
        if (!searchWaiting.containsKey(id)) return;
        String query=e.getMessage().trim();
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> {
            Player p=Bukkit.getPlayer(id); if (p==null) return;
            searchWaiting.remove(id);
            if (query.equalsIgnoreCase("cancel")) { p.sendMessage(color("§7Search cancelled.")); openScanner(p,0); return; }
            if (!query.matches("[A-Za-z0-9_]{3,16}")) { p.sendMessage(color("§cInvalid Minecraft username. Search cancelled.")); openScanner(p,0); return; }
            Player target=Bukkit.getPlayerExact(query);
            if (target==null) p.sendMessage(color("§eNo online player with that exact name. Offline profile search is not enabled."));
            else openInspect(p,target,0);
        });
    }
    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title=legacy.serialize(e.getView().title());
        if (!(title.contains("Player Scanner") || title.contains("INSPECT") || title.contains("CONFIRM WIPE") || title.contains("ACTIVE") || title.contains("WIPE DETAILS"))) return;
        e.setCancelled(true);
        if (e.getClickedInventory()==null || e.getClickedInventory()!=e.getView().getTopInventory()) return;
        int slot=e.getRawSlot();
        if (title.contains("Player Scanner")) {
            if (slot==45) { openScanner(p,0); return; }
            if (slot==46) {
                long now=System.currentTimeMillis(); long last=refreshAt.getOrDefault(p.getUniqueId(),0L);
                if (now-last < getConfig().getLong("profile.refresh-cooldown-seconds",2)*1000) { p.sendMessage(color("§cPlease wait before refreshing again.")); return; }
                refreshAt.put(p.getUniqueId(),now); openScanner(p,0); return;
            }
            if (slot==48) { searchWaiting.put(p.getUniqueId(),"scanner"); p.closeInventory(); p.sendMessage(color("§dType an exact Minecraft username in chat, or type cancel.")); return; }
            if (slot==49) { openWipes(p,0); return; }
            if (slot==51) { if (p.hasPermission("suswipe.settings")) p.sendMessage(color("§7Edit settings in plugins/SusWipe/config.yml, then use /suswipe reload.")); else p.sendMessage(color("§cYou do not have permission: suswipe.settings")); return; }
            if (slot==52) { p.closeInventory(); return; }
            if (slot==53) { p.sendMessage(color("§7There are no additional online-player pages.")); return; }
            ItemStack clicked=e.getCurrentItem();
            if (clicked!=null && clicked.getType()==Material.PLAYER_HEAD && clicked.getItemMeta() instanceof SkullMeta sm && sm.getOwningPlayer()!=null) {
                Player target=Bukkit.getPlayer(sm.getOwningPlayer().getUniqueId()); if (target!=null) openInspect(p,target,0);
            }
        } else if (title.contains("INSPECT")) {
            UUID tid=inspecting.get(p.getUniqueId()); Player target=tid==null?null:Bukkit.getPlayer(tid);
            if (slot==10) {
                if (!allowed(p,"suswipe.teleport")) return;
                if (target==null) { p.sendMessage(color("§cThat player is no longer online.")); return; }
                sessions.putIfAbsent(p.getUniqueId(),new Session(p.getLocation().clone(),p.getGameMode(),p.getAllowFlight(),p.isFlying(),System.currentTimeMillis()));
                p.setGameMode(GameMode.SPECTATOR); p.teleport(target.getLocation()); p.closeInventory();
                p.sendMessage(color("§aSpectator inspection started. Use /sus return to go back."));
            } else if (slot==14) {
                if (!allowed(p,"suswipe.wipe")) return;
                if (target==null) { p.sendMessage(color("§cThat player is no longer online.")); return; }
                openConfirm(p,new Pending(target.getUniqueId(),target.getName(),1,getConfig().getString("wipe.default-reason","Rule violation"),p.getName(),p.getUniqueId()));
            } else if (slot==16) openScanner(p,0);
            else if (slot==22) p.closeInventory();
        } else if (title.contains("CONFIRM WIPE")) {
            Pending a=pending.get(p.getUniqueId());
            if (slot==13 && a!=null) { pending.remove(p.getUniqueId()); applyWipe(p,a); p.closeInventory(); }
            else if (slot==15) { pending.remove(p.getUniqueId()); p.closeInventory(); p.sendMessage(color("§7Wipe cancelled.")); }
        } else if (title.contains("ACTIVE")) {
            if (slot==49) openScanner(p,0);
            else if (slot==45 || slot==53) p.sendMessage(color("§7Pagination will be available when more than 36 active wipes are present."));
            else {
                ItemStack it=e.getCurrentItem();
                if (it!=null && it.getType()==Material.PLAYER_HEAD && it.getItemMeta() instanceof SkullMeta sm && sm.getOwningPlayer()!=null) {
                    Wipe w=wipes.get(sm.getOwningPlayer().getUniqueId());
                    if (w!=null) {
                        Inventory info=Bukkit.createInventory(p,27,legacy.deserialize(color("§8§lWIPE DETAILS")));
                        info.setItem(13,head(w.uuid(),"§c"+w.name(),"§7Reason: §f"+w.reason(),"§7Issuer: §f"+w.issuer(),"§7Duration: §f"+w.days()+" days","§7Expires: §f"+fmt(w.expires()),"§7Remaining: §f"+remaining(w.expires())));
                        info.setItem(11,item(Material.LIME_CONCRETE,"§a§lUNWIPE","§7Remove this SusWipe ban."));
                        info.setItem(15,item(Material.ARROW,"§e§lBack"));
                        info.setItem(22,item(Material.BARRIER,"§c§lClose")); p.openInventory(info);
                    }
                }
            }
        } else if (title.contains("WIPE DETAILS")) {
            ItemStack it=e.getView().getTopInventory().getItem(13);
            if (slot==11 && it!=null && it.getItemMeta() instanceof SkullMeta sm && sm.getOwningPlayer()!=null) {
                if (!allowed(p, "suswipe.unwipe")) return;
                Wipe w=wipes.remove(sm.getOwningPlayer().getUniqueId());
                if (w!=null) { saveWipes(); p.sendMessage(color("§aRemoved SusWipe from "+w.name()+".")); audit("UNWIPE",p.getName(),w.uuid(),"GUI"); }
                openWipes(p,0);
            } else if (slot==15) openWipes(p,0); else if (slot==22) p.closeInventory();
        }
    }
    @EventHandler public void onDrag(InventoryDragEvent e) {
        String title=legacy.serialize(e.getView().title());
        if (title.contains("Player Scanner") || title.contains("INSPECT") || title.contains("CONFIRM WIPE") || title.contains("ACTIVE") || title.contains("WIPE DETAILS")) e.setCancelled(true);
    }
}

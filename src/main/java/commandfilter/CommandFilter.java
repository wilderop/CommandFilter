package commandfilter;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class CommandFilter extends JavaPlugin implements Listener, TabExecutor {

    /**
     * Newly discovered commands in this set default to player-visible.
     * Existing config entries are never overwritten.
     */
    private static final Set<String> SUGGESTED_PLAYER = Set.of(
            "help", "?", "list", "me", "say", "tell", "msg", "w", "r", "reply", "whisper", "m",
            "home", "sethome", "delhome", "newhome", "spawn", "tpa", "tpahere", "tpaccept", "tpdeny",
            "back", "warp", "warps", "rtp",
            "cape", "spawncape",
            "council", "proposals", "activity", "councilboard", "pcboard", "activityboard",
            "restore",
            "topkiller", "topgui", "top", "stats", "profile",
            "vote", "discordlink", "nick", "ignore", "report",
            "register", "login", "verify", "event", "contest", "die"
    );

    private File configFile;
    private FileConfiguration config;
    private final Map<String, String> visibility = new TreeMap<>();
    private final Set<String> newCommands = new LinkedHashSet<>();
    private volatile Set<String> playerVisible = Set.of();
    private BukkitTask delayedScanTask;

    @Override
    public void onEnable() {
        configFile = new File(getDataFolder(), "config.yml");
        loadFromDisk();

        getServer().getPluginManager().registerEvents(this, this);

        var command = getCommand("commandfilter");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        Bukkit.getScheduler().runTask(this, this::scanServerCommands);
        Bukkit.getScheduler().runTaskLater(this, this::scanServerCommands, 20L);
        Bukkit.getScheduler().runTaskLater(this, this::scanServerCommands, 100L);

        getLogger().info("CommandFilter enabled — hiding commands via PlayerCommandSendEvent. OP: /commandfilter");
    }

    public boolean isBypass(Player player) {
        return player.isOp() || player.hasPermission("commandfilter.bypass");
    }

    public boolean isPlayerVisible(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (playerVisible.contains(n)) {
            return true;
        }
        int colon = n.indexOf(':');
        return colon >= 0 && playerVisible.contains(n.substring(colon + 1));
    }

    public Map<String, String> visibilityMap() {
        return visibility;
    }

    public Set<String> newCommands() {
        return newCommands;
    }

    public void toggleVisibility(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        String current = visibility.getOrDefault(key, "op");
        String next = "player".equals(current) ? "op" : "player";
        visibility.put(key, next);
        newCommands.remove(key);
        rebuildPlayerVisible();
        saveToDisk();
        refreshOnlinePlayers();
    }

    public int scanServerCommands() {
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        int added = 0;

        for (Map.Entry<String, Command> entry : known.entrySet()) {
            String label = entry.getKey().toLowerCase(Locale.ROOT);
            if (label.contains(":")) {
                continue;
            }
            Command cmd = entry.getValue();
            added += addIfMissing(label);
            for (String alias : cmd.getAliases()) {
                String norm = alias.toLowerCase(Locale.ROOT);
                if (!norm.contains(":")) {
                    added += addIfMissing(norm);
                }
            }
        }

        if (added > 0) {
            saveToDisk();
            rebuildPlayerVisible();
            refreshOnlinePlayers();
            getLogger().info("Added " + added + " newly registered command(s) to config.yml");
        }
        return added;
    }

    private int addIfMissing(String label) {
        if (visibility.containsKey(label)) {
            return 0;
        }
        String vis = SUGGESTED_PLAYER.contains(label) ? "player" : "op";
        visibility.put(label, vis);
        newCommands.add(label);
        return 1;
    }

    private void rebuildPlayerVisible() {
        Set<String> visible = visibility.entrySet().stream()
                .filter(e -> "player".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        playerVisible = visible;
    }

    public void refreshOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isBypass(player)) {
                player.updateCommands();
            }
        }
    }

    private void loadFromDisk() {
        visibility.clear();
        newCommands.clear();
        if (!configFile.exists()) {
            getDataFolder().mkdirs();
            config = new YamlConfiguration();
            rebuildPlayerVisible();
            return;
        }

        config = YamlConfiguration.loadConfiguration(configFile);
        if (config.isConfigurationSection("commands")) {
            for (String key : config.getConfigurationSection("commands").getKeys(false)) {
                String vis = config.getString("commands." + key, "op").trim().toLowerCase(Locale.ROOT);
                if (!"player".equals(vis)) {
                    vis = "op";
                }
                visibility.put(key.toLowerCase(Locale.ROOT), vis);
            }
        }
        List<String> storedNew = config.getStringList("new-commands");
        for (String name : storedNew) {
            newCommands.add(name.toLowerCase(Locale.ROOT));
        }
        rebuildPlayerVisible();
        getLogger().info("Loaded " + playerVisible.size() + " player-visible command(s) ("
                + visibility.size() + " total, " + newCommands.size() + " new)");
    }

    private void saveToDisk() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        Map<String, Object> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : visibility.entrySet()) {
            sorted.put(e.getKey(), e.getValue());
        }
        config.set("commands", sorted);
        config.setComments("commands", List.of(
                " Edit each entry to 'player' or 'op', or use /commandfilter in-game.",
                " - 'player': Visible in the command list for regular players.",
                " - 'op': Hidden from regular players (OPs and commandfilter.bypass still see all).",
                " Toggles in the GUI apply immediately to online players."
        ));
        config.set("new-commands", new ArrayList<>(newCommands));
        config.setComments("new-commands", List.of(
                " Commands discovered since they were last reviewed in the GUI.",
                " Toggling a command in /commandfilter removes it from this list."
        ));
        try {
            config.save(configFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save config.yml: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (isBypass(player)) {
            return;
        }
        event.getCommands().removeIf(cmd -> !isPlayerVisible(cmd));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player) || isBypass(player)) {
            return;
        }
        String buffer = event.getBuffer();
        if (!buffer.startsWith("/") || buffer.contains(" ")) {
            return;
        }
        String partial = buffer.substring(1).toLowerCase(Locale.ROOT);
        List<String> filtered = playerVisible.stream()
                .filter(s -> s.startsWith(partial))
                .sorted()
                .toList();
        event.setCompletions(filtered);
        event.setHandled(true);
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin() == this) {
            return;
        }
        if (delayedScanTask != null) {
            delayedScanTask.cancel();
        }
        delayedScanTask = Bukkit.getScheduler().runTaskLater(this, this::scanServerCommands, 20L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder(false) instanceof FilterGui gui) {
            event.setCancelled(true);
            gui.handleClick(event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof FilterGui) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String @NotNull [] args) {
        if (args.length == 0) {
            return openGui(sender, "", FilterGui.Mode.ALL, 0);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                loadFromDisk();
                int added = scanServerCommands();
                sender.sendMessage(Component.text("CommandFilter reloaded. "
                        + playerVisible.size() + " player-visible, "
                        + added + " newly scanned.", NamedTextColor.GREEN));
                refreshOnlinePlayers();
                return true;
            }
            case "scan" -> {
                int added = scanServerCommands();
                sender.sendMessage(Component.text("Scan complete. Added " + added
                        + " command(s). " + newCommands.size() + " still marked new.", NamedTextColor.GREEN));
                return true;
            }
            case "gui" -> {
                return openGui(sender, "", FilterGui.Mode.ALL, 0);
            }
            case "search" -> {
                String query = args.length < 2 ? "" : String.join(" ", List.of(args).subList(1, args.length));
                return openGui(sender, query, FilterGui.Mode.ALL, 0);
            }
            default -> {
                return openGui(sender, String.join(" ", args), FilterGui.Mode.ALL, 0);
            }
        }
    }

    private boolean openGui(CommandSender sender, String search, FilterGui.Mode mode, int page) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("The GUI is in-game only. Use /commandfilter reload or scan from console.", NamedTextColor.RED));
            return true;
        }
        scanServerCommands();
        new FilterGui(this, player, search, mode, page).open();
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(List.of("reload", "scan", "gui", "search"));
            options.removeIf(s -> !s.startsWith(partial));
            Collections.sort(options);
            return options;
        }
        return List.of();
    }
}

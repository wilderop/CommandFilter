package commandfilter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FilterGui implements InventoryHolder {

    public enum Mode {
        ALL, PLAYER, OP, NEW;

        Mode next() {
            return switch (this) {
                case ALL -> PLAYER;
                case PLAYER -> OP;
                case OP -> NEW;
                case NEW -> ALL;
            };
        }

        String label() {
            return switch (this) {
                case ALL -> "All";
                case PLAYER -> "Visible to players";
                case OP -> "Hidden from players";
                case NEW -> "Newly discovered";
            };
        }
    }

    private static final int PAGE_SIZE = 45;

    private final CommandFilter plugin;
    private final Player player;
    private Inventory inventory;
    private String search;
    private Mode mode;
    private int page;
    private List<String> view = List.of();

    public FilterGui(CommandFilter plugin, Player player, String search, Mode mode, int page) {
        this.plugin = plugin;
        this.player = player;
        this.search = search == null ? "" : search.toLowerCase(Locale.ROOT).trim();
        this.mode = mode;
        this.page = Math.max(0, page);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open() {
        rebuild(true);
    }

    private void rebuild(boolean reopen) {
        view = filteredCommands();
        int pages = Math.max(1, (int) Math.ceil(view.size() / (double) PAGE_SIZE));
        if (page >= pages) {
            page = pages - 1;
        }
        inventory = Bukkit.createInventory(this, 54, title());
        populate();
        if (reopen) {
            player.openInventory(inventory);
        }
    }

    private Component title() {
        int pages = Math.max(1, (int) Math.ceil(view.size() / (double) PAGE_SIZE));
        String query = search.length() > 16 ? search.substring(0, 16) + "…" : search;
        String extra = query.isEmpty() ? mode.label() : "search: " + query;
        return Component.text("Cmd Filter ", NamedTextColor.DARK_GRAY)
                .append(Component.text((page + 1) + "/" + pages, NamedTextColor.AQUA))
                .append(Component.text(" " + extra, NamedTextColor.GRAY));
    }

    private List<String> filteredCommands() {
        List<String> out = new ArrayList<>();
        for (var entry : plugin.visibilityMap().entrySet()) {
            String name = entry.getKey();
            String vis = entry.getValue();
            if (!search.isEmpty() && !name.contains(search)) {
                continue;
            }
            boolean isNew = plugin.newCommands().contains(name);
            switch (mode) {
                case PLAYER -> {
                    if (!"player".equals(vis)) continue;
                }
                case OP -> {
                    if (!"op".equals(vis)) continue;
                }
                case NEW -> {
                    if (!isNew) continue;
                }
                case ALL -> {
                }
            }
            out.add(name);
        }
        return out;
    }

    private void populate() {
        inventory.clear();
        int start = page * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = start + i;
            if (index >= view.size()) {
                break;
            }
            inventory.setItem(i, commandIcon(view.get(index)));
        }

        int pages = Math.max(1, (int) Math.ceil(view.size() / (double) PAGE_SIZE));
        if (page > 0) {
            inventory.setItem(45, navItem(Material.ARROW, "Previous page", NamedTextColor.YELLOW));
        }
        inventory.setItem(46, searchItem());
        inventory.setItem(47, filterItem());
        inventory.setItem(49, infoItem());
        inventory.setItem(51, navItem(Material.COMPASS, "Rescan commands", NamedTextColor.AQUA,
                "Looks for commands from newly loaded plugins"));
        if (page < pages - 1) {
            inventory.setItem(52, navItem(Material.ARROW, "Next page", NamedTextColor.YELLOW));
        }
        inventory.setItem(53, navItem(Material.BARRIER, "Close", NamedTextColor.RED));
    }

    private ItemStack commandIcon(String name) {
        boolean visible = "player".equals(plugin.visibilityMap().getOrDefault(name, "op"));
        boolean isNew = plugin.newCommands().contains(name);
        Material material = isNew && !visible ? Material.YELLOW_CONCRETE
                : visible ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        NamedTextColor color = visible ? NamedTextColor.GREEN : NamedTextColor.RED;
        meta.displayName(Component.text("/" + name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(visible ? "Players CAN see this" : "Hidden from players",
                visible ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        if (isNew) {
            lore.add(Component.text("NEW — not reviewed yet", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Click to " + (visible ? "hide from" : "show to") + " players",
                NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Applies immediately, no restart", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (visible) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack searchItem() {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(search.isEmpty() ? "Search" : "Search: " + search, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Type /commandfilter search <text>", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(search.isEmpty() ? "No filter" : "Click to clear", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filterItem() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Filter: " + mode.label(), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Click to cycle: All → Visible → Hidden → New", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        long playerCount = plugin.visibilityMap().values().stream().filter("player"::equals).count();
        long opCount = plugin.visibilityMap().size() - playerCount;
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Command Filter", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(playerCount + " visible to players", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(opCount + " hidden", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(plugin.newCommands().size() + " newly discovered", NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(view.size() + " shown on this filter", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Lime = players see it. Red = hidden.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Yellow = new, currently hidden.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack navItem(Material material, String name, NamedTextColor color, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        if (loreLines.length > 0) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    private float visiblePitch(String name) {
        return "player".equals(plugin.visibilityMap().getOrDefault(name, "op")) ? 1.4f : 0.8f;
    }

    public void handleClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player clicker) || !clicker.getUniqueId().equals(player.getUniqueId())) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != inventory) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0) {
            return;
        }

        if (slot < PAGE_SIZE) {
            int index = page * PAGE_SIZE + slot;
            if (index >= view.size()) {
                return;
            }
            String name = view.get(index);
            plugin.toggleVisibility(name);
            clicker.playSound(clicker, Sound.UI_BUTTON_CLICK, 0.4f, visiblePitch(name));
            view = filteredCommands();
            populate();
            return;
        }

        switch (slot) {
            case 45 -> {
                if (page > 0) {
                    page--;
                    rebuild(true);
                }
            }
            case 46 -> {
                if (!search.isEmpty()) {
                    search = "";
                    page = 0;
                    rebuild(true);
                } else {
                    clicker.sendMessage(Component.text("Type /commandfilter search <text> to filter the list.", NamedTextColor.YELLOW));
                }
            }
            case 47 -> {
                mode = mode.next();
                page = 0;
                rebuild(true);
            }
            case 51 -> {
                int added = plugin.scanServerCommands();
                clicker.sendMessage(Component.text("Scan complete. Added " + added + " command(s).", NamedTextColor.GREEN));
                rebuild(true);
            }
            case 52 -> {
                int pages = Math.max(1, (int) Math.ceil(view.size() / (double) PAGE_SIZE));
                if (page < pages - 1) {
                    page++;
                    rebuild(true);
                }
            }
            case 53 -> clicker.closeInventory();
            default -> {
            }
        }
    }
}

package net.clanimg.crossClipboard;

import net.clanimg.crossClipboard.sync.SyncService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class CrossClipboardCommand implements TabExecutor {

    private static final String ADMIN_PERMISSION = "crossclipboard.admin";
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("status", "push", "pull", "clear");

    private final CrossClipboard plugin;
    private final SyncService sync;
    private final Messages messages;

    CrossClipboardCommand(CrossClipboard plugin, SyncService sync, Messages messages) {
        this.plugin = plugin;
        this.sync = sync;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);
        String subcommand = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (subcommand.equals("reload")) {
            if (!admin) {
                sender.sendMessage(messages.get("no-permission"));
            } else {
                plugin.reloadSettings(sender);
            }
            return true;
        }

        if (!PLAYER_SUBCOMMANDS.contains(subcommand)) {
            sender.sendMessage(messages.get(admin ? "usage-admin" : "usage", Map.of("command", label)));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get("players-only"));
            return true;
        }
        if (!player.hasPermission(SyncService.USE_PERMISSION)) {
            player.sendMessage(messages.get("no-permission"));
            return true;
        }

        switch (subcommand) {
            case "status" -> sync.status(player);
            case "push" -> sync.push(player);
            case "pull" -> sync.pull(player);
            case "clear" -> sync.clear(player);
            default -> throw new IllegalStateException("Unhandled subcommand " + subcommand);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return java.util.stream.Stream
                .concat(PLAYER_SUBCOMMANDS.stream(),
                        sender.hasPermission(ADMIN_PERMISSION) ? java.util.stream.Stream.of("reload") : java.util.stream.Stream.empty())
                .filter(name -> name.startsWith(typed))
                .toList();
    }
}

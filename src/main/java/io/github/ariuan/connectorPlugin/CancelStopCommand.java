package io.github.ariuan.connectorPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class CancelStopCommand implements CommandExecutor {
    private final ConnectorPlugin plugin;

    public CancelStopCommand(ConnectorPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("connector.cancelstop")) {
            sender.sendMessage(Component.text("You don't have permission to use this command", NamedTextColor.RED));
            return true;
        }

        // Check if there's a scheduled shutdown first
        if (!plugin.getShutdownManager().hasScheduledShutdown()) {
            sender.sendMessage(Component.text("No shutdown is currently scheduled", NamedTextColor.YELLOW));
            return true;
        }

        // Execute the cancellation asynchronously since it makes an API call
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = plugin.getShutdownManager().cancelShutdownViaApi();
            
            // Send result message on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (success) {
                    sender.sendMessage(Component.text("Shutdown cancelled successfully", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Failed to cancel shutdown - check server logs for details", NamedTextColor.RED));
                }
            });
        });

        return true;
    }
}

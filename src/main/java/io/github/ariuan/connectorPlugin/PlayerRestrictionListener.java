package io.github.ariuan.connectorPlugin;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;

public class PlayerRestrictionListener implements Listener {
    private final PlayerVerificationManager verificationManager;

    public PlayerRestrictionListener(PlayerVerificationManager verificationManager) {
        this.verificationManager = verificationManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerPickupItem(PlayerPickupItemEvent event) {
        Player player = event.getPlayer();
        if (!verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§cYou must be verified before picking up items.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§cYou must be verified before dropping items.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§cYou must be verified before interacting with blocks.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("§cYou must be verified before sending chat messages.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player) {
            Player player = (Player) entity;
            if (!verificationManager.isVerified(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Check if the damager is a player
        Entity damager = event.getDamager();
        if (damager instanceof Player) {
            Player player = (Player) damager;
            if (!verificationManager.isVerified(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage("§cYou must be verified before dealing damage.");
            }
        }

        // Also check if the victim is a player (prevent them from being damaged)
        Entity entity = event.getEntity();
        if (entity instanceof Player) {
            Player player = (Player) entity;
            if (!verificationManager.isVerified(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }
}

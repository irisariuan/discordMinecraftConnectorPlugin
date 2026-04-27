package io.github.ariuan.connectorPlugin.paper;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;

public class PlayerRestrictionListener implements Listener {
    private final PlayerVerificationManager verificationManager;

    public PlayerRestrictionListener(PlayerVerificationManager verificationManager) {
        this.verificationManager = verificationManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && !verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!verificationManager.isVerified(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!verificationManager.isVerified(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncChatEvent event) {
        if (!verificationManager.isVerified(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && !verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player && !verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityTargetLiving(EntityTargetLivingEntityEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && !verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityTarget(EntityTargetEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && !verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!verificationManager.isVerified(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        var player = event.getPlayer();
        if (!verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(ConnectorPlugin.getInstance(), player::closeInventory);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryChange(InventoryClickEvent event) {
        Inventory inventory = event.getInventory();
        if (inventory instanceof Player player && !verificationManager.isVerified(player.getUniqueId())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(ConnectorPlugin.getInstance(), player::closeInventory);
        }
    }
}

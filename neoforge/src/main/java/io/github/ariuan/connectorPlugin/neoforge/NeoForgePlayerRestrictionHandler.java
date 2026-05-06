package io.github.ariuan.connectorPlugin.neoforge;

import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * NeoForge event listener that restricts unverified players from interacting
 * with the world until their Discord account is linked.
 */
public class NeoForgePlayerRestrictionHandler {

	private final NeoForgePlayerVerificationManager verificationManager;

	public NeoForgePlayerRestrictionHandler(
		NeoForgePlayerVerificationManager verificationManager
	) {
		this.verificationManager = verificationManager;
	}

	private boolean isUnverified(UUID uuid) {
		return !verificationManager.isVerified(uuid);
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onEntityPickupItem(ItemEntityPickupEvent.Pre event) {
		if (
			event.getPlayer() instanceof ServerPlayer player &&
			isUnverified(player.getUUID())
		) {
			event.setCanPickup(TriState.FALSE);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onItemToss(ItemTossEvent event) {
		if (isUnverified(event.getPlayer().getUUID())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onPlayerInteractRightClickBlock(
		PlayerInteractEvent.RightClickBlock event
	) {
		if (isUnverified(event.getEntity().getUUID())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onPlayerInteractRightClickItem(
		PlayerInteractEvent.RightClickItem event
	) {
		if (isUnverified(event.getEntity().getUUID())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onPlayerInteractLeftClickBlock(
		PlayerInteractEvent.LeftClickBlock event
	) {
		if (isUnverified(event.getEntity().getUUID())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onPlayerInteractEntitySpecific(
		PlayerInteractEvent.EntityInteractSpecific event
	) {
		if (isUnverified(event.getEntity().getUUID())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onServerChat(ServerChatEvent event) {
		if (isUnverified(event.getPlayer().getUUID())) {
			event.setCanceled(true);
			event
				.getPlayer()
				.sendSystemMessage(
					Component.literal(
						"You must verify your Discord account before chatting."
					).withStyle(ChatFormatting.RED)
				);
		}
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
		Entity entity = event.getEntity();
		if (
			entity instanceof ServerPlayer player &&
			isUnverified(player.getUUID())
		) {
			event.setCanceled(true);
		}
		// Also cancel damage dealt by unverified players
		if (
			event.getSource().getEntity() instanceof ServerPlayer attacker &&
			isUnverified(attacker.getUUID())
		) {
			event.setCanceled(true);
		}
	}

	// Note: NeoForge 21.11.42 does not provide a LivingMoveEvent equivalent.
	// Movement restriction for unverified players is not implemented at the event level.
	// Consider using EntityTeleportEvent to restrict teleportation if needed:
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onEntityTeleport(EntityTeleportEvent event) {
		if (
			event.getEntity() instanceof ServerPlayer player &&
			isUnverified(player.getUUID())
		) {
			event.setCanceled(true);
		}
	}
}

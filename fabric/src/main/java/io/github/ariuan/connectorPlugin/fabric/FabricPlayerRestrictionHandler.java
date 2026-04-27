package io.github.ariuan.connectorPlugin.fabric;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers Fabric API event callbacks that restrict unverified players from
 * interacting with the world until their Discord account is linked.
 *
 * <p>Fabric API does not expose item-pickup or inventory-click events without
 * mixins; movement is restricted by teleporting the player back to their spawn
 * position on every server tick while unverified.
 */
public class FabricPlayerRestrictionHandler {
    private final FabricPlayerVerificationManager verificationManager;
    /** Stores the position where each unverified player joined, to teleport them back. */
    private final Map<UUID, double[]> frozenPositions = new ConcurrentHashMap<>();

    public FabricPlayerRestrictionHandler(FabricPlayerVerificationManager verificationManager) {
        this.verificationManager = verificationManager;
    }

    /**
     * Record the join position of a new player so we can freeze them there.
     * Call this before the verification result arrives.
     */
    public void recordJoinPosition(ServerPlayer player) {
        frozenPositions.put(player.getUUID(),
            new double[]{player.getX(), player.getY(), player.getZ()});
    }

    /** Remove freeze data when the player has been verified or has left. */
    public void clearFreezeData(UUID uuid) {
        frozenPositions.remove(uuid);
    }

    /** Register all event callbacks. */
    public void register(MinecraftServer server) {
        // Block interaction
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && isUnverified(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Entity interaction (right-click)
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && isUnverified(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Item use (right-click in air)
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide() && isUnverified(player.getUUID())) {
                return InteractionResultHolder.fail(player.getItemInHand(hand));
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });

        // Block attack (left-click)
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() && isUnverified(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Entity attack
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && isUnverified(player.getUUID())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // Chat
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (isUnverified(sender.getUUID())) {
                sender.sendSystemMessage(Component.literal(
                    "You must verify your Discord account before chatting.")
                    .withStyle(ChatFormatting.RED));
                return false;
            }
            return true;
        });

        // Movement – freeze unverified players by teleporting them back each tick
        ServerTickEvents.END_SERVER_TICK.register(tickServer -> {
            for (ServerPlayer player : tickServer.getPlayerList().getPlayers()) {
                UUID uuid = player.getUUID();
                if (isUnverified(uuid)) {
                    double[] pos = frozenPositions.get(uuid);
                    if (pos != null) {
                        // Only teleport if they moved significantly (>0.1 blocks) to avoid spam
                        double dx = player.getX() - pos[0];
                        double dz = player.getZ() - pos[2];
                        if (dx * dx + dz * dz > 0.01) {
                            player.teleportTo(pos[0], pos[1], pos[2]);
                        }
                    }
                }
            }
        });
    }

    private boolean isUnverified(UUID uuid) {
        return !verificationManager.isVerified(uuid);
    }
}

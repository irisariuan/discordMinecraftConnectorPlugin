package io.github.ariuan.connectorPlugin.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The connector's method surface on top of {@link IpcClient}: it answers the
 * calls the bot makes (all delegated to the platform's {@link IPlatformAdapter})
 * and exposes typed calls in the other direction for the verification and
 * shutdown managers.
 *
 * <p>Outbound calls block and are expected to run off the main server thread.
 */
public class ConnectorApi implements AutoCloseable {

	private final IpcClient ipc;
	private final IPlatformAdapter adapter;

	public ConnectorApi(
		Path socketPath,
		String platform,
		String version,
		IPlatformAdapter adapter
	) {
		this.adapter = adapter;
		this.ipc = new IpcClient(socketPath, platform, version, adapter);
		registerHandlers();
	}

	/** Start dialling the bot's socket. */
	public void start() {
		ipc.start();
	}

	/** True while the bot is attached. */
	public boolean isConnected() {
		return ipc.isConnected();
	}

	// ── Calls the bot makes on us ────────────────────────────────────────────

	private void registerHandlers() {
		ipc.registerHandler("ping", params -> new JsonObject());

		ipc.registerHandler("command.run", params -> {
			String command = requireString(params, "command");
			CommandResult result = adapter.runCommandAndCapture(command);
			JsonObject response = new JsonObject();
			response.addProperty("success", result.success);
			response.addProperty("output", result.output);
			response.addProperty("logger", result.loggerOutput);
			return response;
		});

		ipc.registerHandler("player.list", params -> {
			JsonArray players = new JsonArray();
			for (IPlayerInfo player : adapter.getOnlinePlayers()) {
				JsonObject entry = new JsonObject();
				entry.addProperty("name", player.getName());
				entry.addProperty("id", player.getUUID().toString());
				players.add(entry);
			}
			JsonObject response = new JsonObject();
			response.add("players", players);
			return response;
		});

		ipc.registerHandler("player.register", params -> {
			String otp = requireString(params, "otp");
			String playerName = optString(params, "playerName");
			String uuid = optString(params, "uuid");

			IPlayerInfo player;
			if (playerName != null) {
				player = adapter.getPlayerByName(playerName);
			} else if (uuid != null) {
				player = adapter.getPlayerByUUID(parseUUID(uuid));
			} else {
				throw new IllegalArgumentException("Missing playerName or uuid");
			}
			if (player == null) throw new IllegalArgumentException("Player not found");

			adapter.sendMessageToPlayer(
				player.getUUID(),
				"Here's your OTP: " + otp
			);
			JsonObject response = new JsonObject();
			response.addProperty("uuid", player.getUUID().toString());
			return response;
		});

		ipc.registerHandler("player.markVerified", params -> {
			UUID uuid = parseUUID(requireString(params, "uuid"));
			IPlayerInfo player = adapter.getPlayerByUUID(uuid);
			if (player == null) throw new IllegalArgumentException("Player not found");
			adapter.markPlayerVerified(player.getUUID());
			return new JsonObject();
		});

		ipc.registerHandler("logs.get", params -> {
			JsonArray lines = new JsonArray();
			for (LogCaptureHandler.LogEntry entry : adapter
				.getLogCaptureHandler()
				.getRecentLogs()) {
				JsonObject line = new JsonObject();
				line.addProperty("timestamp", entry.timestamp);
				line.addProperty("message", entry.message);
				line.addProperty("type", "server");
				lines.add(line);
			}
			JsonObject response = new JsonObject();
			response.add("lines", lines);
			return response;
		});

		ipc.registerHandler("shutdown.schedule", params -> {
			if (!params.has("tick") || params.get("tick").isJsonNull()) {
				throw new IllegalArgumentException("Missing tick");
			}
			long tick;
			try {
				tick = params.get("tick").getAsLong();
			} catch (NumberFormatException | IllegalStateException e) {
				throw new IllegalArgumentException("Non-numeric tick");
			}
			JsonObject response = new JsonObject();
			response.addProperty("success", adapter.scheduleShutdown(tick, false));
			return response;
		});

		ipc.registerHandler("shutdown.cancel", params -> {
			JsonObject response = new JsonObject();
			response.addProperty("success", adapter.cancelShutdown());
			return response;
		});

		ipc.registerHandler("shutdown.status", params -> {
			JsonObject response = new JsonObject();
			response.addProperty("scheduled", adapter.hasScheduledShutdown());
			return response;
		});

		ipc.registerHandler("components.list", params -> {
			JsonArray components = new JsonArray();
			adapter.getLoadedComponents().forEach(components::add);
			JsonObject response = new JsonObject();
			response.add("components", components);
			return response;
		});
	}

	// ── Calls we make on the bot ─────────────────────────────────────────────

	/** Whether the player is linked to a Discord account. */
	public boolean verify(UUID uuid, String playerName) throws IOException {
		JsonObject params = playerParams(uuid, playerName);
		JsonObject result = ipc.request("player.verify", params);
		return result.has("verified") && result.get("verified").getAsBoolean();
	}

	/**
	 * Report a session heartbeat. The bot charges the play fee and answers with
	 * whether the player should be kicked.
	 */
	public boolean play(
		UUID uuid,
		String playerName,
		long onlineTime,
		boolean disconnect
	) throws IOException {
		JsonObject params = playerParams(uuid, playerName);
		params.addProperty("onlineTime", onlineTime);
		params.addProperty("disconnect", disconnect);
		JsonObject result = ipc.request("player.play", params);
		return result.has("kick") && result.get("kick").getAsBoolean();
	}

	/** The bot's verdict on an in-game request to cancel a scheduled shutdown. */
	public record CancelDecision(boolean allowed, String reason) {}

	/** Ask the bot to authorise (and charge for) cancelling a shutdown. */
	public CancelDecision requestCancelShutdown(UUID uuid, String playerName)
		throws IOException {
		JsonObject result = ipc.request(
			"shutdown.requestCancel",
			playerParams(uuid, playerName)
		);
		return new CancelDecision(
			result.has("allowed") && result.get("allowed").getAsBoolean(),
			optString(result, "reason")
		);
	}

	private static JsonObject playerParams(UUID uuid, String playerName) {
		JsonObject params = new JsonObject();
		params.addProperty("uuid", uuid.toString());
		params.addProperty("playerName", playerName);
		return params;
	}

	// ── Parameter helpers ────────────────────────────────────────────────────

	private static String optString(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull()
			? json.get(key).getAsString()
			: null;
	}

	private static String requireString(JsonObject json, String key) {
		String value = optString(json, key);
		if (value == null) throw new IllegalArgumentException("Missing " + key);
		return value;
	}

	private static UUID parseUUID(String uuid) {
		try {
			return UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid uuid");
		}
	}

	@Override
	public void close() {
		ipc.close();
	}
}

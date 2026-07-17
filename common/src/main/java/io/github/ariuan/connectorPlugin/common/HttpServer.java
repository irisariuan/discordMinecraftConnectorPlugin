package io.github.ariuan.connectorPlugin.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Platform-agnostic NanoHTTPD-based HTTP server.
 * All game interactions are delegated to the injected {@link IPlatformAdapter}.
 */
public class HttpServer extends NanoHTTPD {

	private final IPlatformAdapter adapter;

	public HttpServer(int port, IPlatformAdapter adapter) throws IOException {
		super(port);
		this.adapter = adapter;
		start(SOCKET_READ_TIMEOUT, false);
		adapter.getLogger().info("HTTP Server started on port " + port);
	}

	/** Stop the HTTP server (delegates to {@link fi.iki.elonen.NanoHTTPD#stop()}). */
	@Override
	public void stop() {
		super.stop();
	}

	@Override
	public Response serve(IHTTPSession session) {
		try {
			if (Method.POST.equals(session.getMethod())) {
				String uri = session.getUri();
				Map<String, String> body = new HashMap<>();
				session.parseBody(body);
				JsonObject json;
				try {
					json = JsonParser.parseString(
						body.get("postData")
					).getAsJsonObject();
				} catch (Exception e) {
					return badRequest("Bad Request, expected a JSON body");
				}

				switch (uri) {
					case "/runCommand" -> {
						String command = optString(json, "command");
						if (command == null) {
							return badRequest("Bad Request, Missing command");
						}
						CommandResult result = adapter.runCommandAndCapture(
							command
						);
						JsonObject response = new JsonObject();
						response.addProperty("success", result.success);
						response.addProperty("output", result.output);
						response.addProperty("logger", result.loggerOutput);
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							response.toString()
						);
					}
					case "/shutdown" -> {
						Long tickDelay = optLong(json, "tick");
						if (tickDelay == null) {
							return badRequest(
								"Bad Request, Missing or non-numeric tick"
							);
						}
						boolean successful = adapter.scheduleShutdown(
							tickDelay,
							false
						);
						JsonObject response = new JsonObject();
						response.addProperty("success", successful);
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							response.toString()
						);
					}
					case "/register" -> {
						String playerName = optString(json, "playerName");
						String uuid = optString(json, "uuid");
						String otp = optString(json, "otp");
						if (otp == null) {
							return badRequest("Bad Request, Missing otp");
						}
						if (playerName == null && uuid == null) {
							return badRequest(
								"Bad Request, Missing playerName or uuid"
							);
						}

						IPlayerInfo player;
						if (playerName != null) {
							player = adapter.getPlayerByName(playerName);
						} else {
							UUID parsed = parseUUID(uuid);
							if (parsed == null) {
								return badRequest("Bad Request, Invalid uuid");
							}
							player = adapter.getPlayerByUUID(parsed);
						}
						if (player == null) {
							return newFixedLengthResponse(
								Response.Status.BAD_REQUEST,
								MIME_PLAINTEXT,
								"Player not found"
							);
						}
						adapter.sendMessageToPlayer(
							player.getUUID(),
							"Here's your OTP: " + otp
						);
						JsonObject response = new JsonObject();
						response.addProperty("success", true);
						response.addProperty(
							"uuid",
							player.getUUID().toString()
						);
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							response.toString()
						);
					}
					case "/registered" -> {
						String uuid = optString(json, "uuid");
						if (uuid == null) {
							return badRequest("Bad Request, Missing uuid");
						}
						UUID parsed = parseUUID(uuid);
						if (parsed == null) {
							return badRequest("Bad Request, Invalid uuid");
						}
						IPlayerInfo player = adapter.getPlayerByUUID(parsed);
						if (player == null) {
							return newFixedLengthResponse(
								Response.Status.BAD_REQUEST,
								MIME_PLAINTEXT,
								"Player not found"
							);
						}
						adapter.markPlayerVerified(player.getUUID());
						return newFixedLengthResponse(
							Response.Status.OK,
							MIME_PLAINTEXT,
							"Ok"
						);
					}
				}
			} else if (Method.GET.equals(session.getMethod())) {
				String uri = session.getUri();
				switch (uri) {
					case "/logs": {
						JsonArray arr = buildLogsArray();
						adapter.getLogger().info("Called logs");
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							arr.toString()
						);
					}
					case "/players": {
						JsonArray arr = new JsonArray();
						adapter
							.getOnlinePlayers()
							.forEach(player -> {
								JsonObject obj = new JsonObject();
								obj.addProperty(
									"uuid",
									player.getUUID().toString()
								);
								obj.addProperty("name", player.getName());
								arr.add(obj);
							});
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							arr.toString()
						);
					}
					case "/cancelShutdown": {
						boolean successful = adapter.cancelShutdown();
						JsonObject response = new JsonObject();
						response.addProperty("success", successful);
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							response.toString()
						);
					}
					case "/shutdown": {
						boolean successful = adapter.scheduleShutdown(
							20 * 60,
							false
						);
						JsonObject response = new JsonObject();
						response.addProperty("success", successful);
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							response.toString()
						);
					}
					case "/shuttingDown": {
						boolean shuttingDown = adapter.hasScheduledShutdown();
						JsonObject response = new JsonObject();
						response.addProperty("result", shuttingDown);
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							response.toString()
						);
					}
					case "/ping": {
						return newFixedLengthResponse(
							Response.Status.OK,
							MIME_PLAINTEXT,
							"Pong!"
						);
					}
					case "/plugins": {
						JsonArray nameList = new JsonArray();
						adapter.getLoadedComponents().forEach(nameList::add);
						JsonObject response = new JsonObject();
						response.add("plugins", nameList);
						return newFixedLengthResponse(
							Response.Status.OK,
							"application/json",
							response.toString()
						);
					}
				}
				return newFixedLengthResponse(
					Response.Status.NOT_FOUND,
					MIME_PLAINTEXT,
					"Not found"
				);
			}
		} catch (Exception e) {
			adapter
				.getLogger()
				.warning("Error handling HTTP request: " + e.getMessage());
			return newFixedLengthResponse(
				Response.Status.INTERNAL_ERROR,
				MIME_PLAINTEXT,
				"Internal error"
			);
		}
		return newFixedLengthResponse(
			Response.Status.NOT_FOUND,
			MIME_PLAINTEXT,
			"Not Found"
		);
	}

	/** Reads an optional string field; {@code null} when absent or JSON null. */
	private static String optString(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull()
			? json.get(key).getAsString()
			: null;
	}

	/** Reads an optional numeric field; {@code null} when absent or not a number. */
	private static Long optLong(JsonObject json, String key) {
		if (!json.has(key) || json.get(key).isJsonNull()) return null;
		try {
			return json.get(key).getAsLong();
		} catch (NumberFormatException | IllegalStateException e) {
			return null;
		}
	}

	/** Parses a UUID, returning {@code null} rather than throwing on bad input. */
	private static UUID parseUUID(String uuid) {
		try {
			return UUID.fromString(uuid);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private Response badRequest(String message) {
		return newFixedLengthResponse(
			Response.Status.BAD_REQUEST,
			MIME_PLAINTEXT,
			message
		);
	}

	private @NotNull JsonArray buildLogsArray() {
		LogCaptureHandler.LogEntry[] entries = adapter
			.getLogCaptureHandler()
			.getRecentLogs();
		JsonArray arr = new JsonArray();
		for (LogCaptureHandler.LogEntry entry : entries) {
			JsonObject json = new JsonObject();
			json.addProperty("timestamp", entry.timestamp);
			json.addProperty("message", entry.message);
			json.addProperty("type", "server");
			arr.add(json);
		}
		return arr;
	}
}

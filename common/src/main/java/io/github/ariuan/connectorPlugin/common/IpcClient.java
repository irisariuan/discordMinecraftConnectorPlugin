package io.github.ariuan.connectorPlugin.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * The plugin's end of the connector IPC: a newline-delimited JSON session over a
 * Unix domain socket that the bot listens on.
 *
 * <p>The connection is symmetric — the bot calls methods registered here through
 * {@link #registerHandler}, and this side calls back with {@link #request}. The
 * socket is dialled on a background thread that reconnects with backoff, so a
 * bot restart detaches the server only until the next attempt succeeds.
 *
 * <p>See {@code docs/IPC.md} in the bot repository for the wire format.
 */
public class IpcClient implements AutoCloseable {

	/** Wire-format version; both sides must agree. */
	public static final int PROTOCOL_VERSION = 1;

	/** Environment variable the bot sets when it launches the server. */
	public static final String SOCKET_ENV_VAR = "CONNECTOR_IPC_SOCKET";

	/** Socket file name used when neither config nor environment says otherwise. */
	public static final String DEFAULT_SOCKET_NAME = "connector.sock";

	/** Default cap on how long {@link #request} waits for a response. */
	private static final long DEFAULT_TIMEOUT_MS = 10_000;

	private static final long INITIAL_BACKOFF_MS = 1_000;
	private static final long MAX_BACKOFF_MS = 30_000;

	/** A method the bot may call on this plugin. */
	@FunctionalInterface
	public interface MethodHandler {
		/** @return the result object, or null for an empty result */
		JsonObject handle(JsonObject params) throws Exception;
	}

	private final Path socketPath;
	private final String platform;
	private final String version;
	private final Logger logger;
	private final IPlatformAdapter adapter;

	private final Map<String, MethodHandler> handlers = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<JsonObject>> pending =
		new ConcurrentHashMap<>();
	private final AtomicLong nextId = new AtomicLong(1);
	private final Object writeLock = new Object();

	/** Handlers run off the reader thread so a slow command cannot stall reads. */
	private final ExecutorService workers = Executors.newCachedThreadPool(
		runnable -> {
			Thread thread = new Thread(runnable, "connector-ipc-worker");
			thread.setDaemon(true);
			return thread;
		}
	);

	private volatile boolean running = false;
	private volatile Writer writer;
	private volatile SocketChannel channel;
	private Thread readerThread;

	public IpcClient(
		Path socketPath,
		String platform,
		String version,
		IPlatformAdapter adapter
	) {
		this.socketPath = socketPath;
		this.platform = platform;
		this.version = version;
		this.adapter = adapter;
		this.logger = adapter.getLogger();
	}

	/**
	 * Where the socket lives: an explicit setting wins, then the environment
	 * variable the bot passes at launch, then {@link #DEFAULT_SOCKET_NAME} in the
	 * server's working directory.
	 */
	public static Path resolveSocketPath(String configured) {
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured.trim()).toAbsolutePath();
		}
		String fromEnv = System.getenv(SOCKET_ENV_VAR);
		if (fromEnv != null && !fromEnv.isBlank()) {
			return Path.of(fromEnv.trim()).toAbsolutePath();
		}
		return Path.of("").toAbsolutePath().resolve(DEFAULT_SOCKET_NAME);
	}

	/** Register a method the bot may call. Must be done before {@link #start}. */
	public void registerHandler(String method, MethodHandler handler) {
		handlers.put(method, handler);
	}

	/** Begin dialling the socket; returns immediately. */
	public synchronized void start() {
		if (running) return;
		running = true;
		readerThread = new Thread(this::connectLoop, "connector-ipc");
		readerThread.setDaemon(true);
		readerThread.start();
		logger.info("Connector IPC attaching to " + socketPath);
	}

	/** True while a session is established. */
	public boolean isConnected() {
		return writer != null;
	}

	// ── Session ──────────────────────────────────────────────────────────────

	private void connectLoop() {
		long backoff = INITIAL_BACKOFF_MS;
		boolean warnedMissing = false;
		while (running) {
			if (!Files.exists(socketPath)) {
				// The bot creates the socket when it launches the server; a
				// manually started server may simply be early.
				if (!warnedMissing) {
					logger.warning(
						"Connector socket " +
						socketPath +
						" does not exist yet, retrying in the background"
					);
					warnedMissing = true;
				}
			} else {
				warnedMissing = false;
				try {
					runSession();
					backoff = INITIAL_BACKOFF_MS;
				} catch (IOException e) {
					if (running) {
						logger.warning(
							"Connector IPC session ended: " + e.getMessage()
						);
					}
				}
			}
			if (!running) break;
			try {
				Thread.sleep(backoff);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
		}
	}

	/** Hold one connection open, reading frames until it closes. */
	private void runSession() throws IOException {
		try (
			SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX)
		) {
			socket.connect(UnixDomainSocketAddress.of(socketPath));
			this.channel = socket;
			this.writer = new BufferedWriter(
				new OutputStreamWriter(
					Channels.newOutputStream(socket),
					StandardCharsets.UTF_8
				)
			);
			sendHello();
			logger.info("Connector IPC connected to " + socketPath);

			BufferedReader reader = new BufferedReader(
				new InputStreamReader(
					Channels.newInputStream(socket),
					StandardCharsets.UTF_8
				)
			);
			String line;
			while (running && (line = reader.readLine()) != null) {
				if (!line.isBlank()) handleLine(line);
			}
		} finally {
			this.writer = null;
			this.channel = null;
			failPending("connection closed");
			if (running) logger.info("Connector IPC disconnected");
		}
	}

	private void sendHello() throws IOException {
		JsonObject hello = new JsonObject();
		hello.addProperty("t", "hello");
		hello.addProperty("v", PROTOCOL_VERSION);
		hello.addProperty("serverPort", adapter.getServerPort());
		hello.addProperty("platform", platform);
		hello.addProperty("version", version);
		writeFrame(hello);
	}

	private void handleLine(String line) {
		JsonObject frame;
		try {
			frame = JsonParser.parseString(line).getAsJsonObject();
		} catch (Exception e) {
			logger.warning("Ignoring malformed IPC frame");
			return;
		}
		String type = optString(frame, "t");
		if (type == null) return;
		switch (type) {
			case "welcome" -> logger.info(
				"Connector IPC attached as server #" +
				(frame.has("serverId") ? frame.get("serverId").getAsString() : "?")
			);
			case "req" -> workers.execute(() -> dispatch(frame));
			case "res" -> completePending(frame);
			default -> {}
		}
	}

	/** Run a bot-initiated request and answer it. */
	private void dispatch(JsonObject frame) {
		String id = optString(frame, "id");
		String method = optString(frame, "method");
		if (id == null || method == null) return;
		MethodHandler handler = handlers.get(method);
		if (handler == null) {
			sendError(id, "Unknown method \"" + method + "\"");
			return;
		}
		JsonElement rawParams = frame.get("params");
		JsonObject params = rawParams != null && rawParams.isJsonObject()
			? rawParams.getAsJsonObject()
			: new JsonObject();
		try {
			JsonObject result = handler.handle(params);
			JsonObject response = new JsonObject();
			response.addProperty("t", "res");
			response.addProperty("id", id);
			response.addProperty("ok", true);
			response.add("result", result == null ? new JsonObject() : result);
			writeFrame(response);
		} catch (Exception e) {
			String message = e.getMessage() == null
				? e.getClass().getSimpleName()
				: e.getMessage();
			logger.warning("IPC method \"" + method + "\" failed: " + message);
			sendError(id, message);
		}
	}

	private void completePending(JsonObject frame) {
		String id = optString(frame, "id");
		if (id == null) return;
		CompletableFuture<JsonObject> future = pending.remove(id);
		if (future == null) return;
		boolean ok = frame.has("ok") && frame.get("ok").getAsBoolean();
		if (ok) {
			JsonElement result = frame.get("result");
			future.complete(
				result != null && result.isJsonObject()
					? result.getAsJsonObject()
					: new JsonObject()
			);
		} else {
			String error = optString(frame, "error");
			future.completeExceptionally(
				new IOException(error == null ? "request failed" : error)
			);
		}
	}

	// ── Outbound requests ────────────────────────────────────────────────────

	/** Call a bot method and block for its result. */
	public JsonObject request(String method, JsonObject params)
		throws IOException {
		return request(method, params, DEFAULT_TIMEOUT_MS);
	}

	/** Call a bot method and block for its result, up to {@code timeoutMs}. */
	public JsonObject request(String method, JsonObject params, long timeoutMs)
		throws IOException {
		if (!isConnected()) {
			throw new IOException("Connector IPC is not connected");
		}
		String id = "p" + nextId.getAndIncrement();
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		pending.put(id, future);

		JsonObject frame = new JsonObject();
		frame.addProperty("t", "req");
		frame.addProperty("id", id);
		frame.addProperty("method", method);
		frame.add("params", params == null ? new JsonObject() : params);
		try {
			writeFrame(frame);
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new IOException("\"" + method + "\" timed out", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("\"" + method + "\" was interrupted", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof IOException io
				? io
				: new IOException(cause == null ? e : cause);
		} finally {
			pending.remove(id);
		}
	}

	// ── Writing ──────────────────────────────────────────────────────────────

	private void sendError(String id, String message) {
		JsonObject response = new JsonObject();
		response.addProperty("t", "res");
		response.addProperty("id", id);
		response.addProperty("ok", false);
		response.addProperty("error", message);
		try {
			writeFrame(response);
		} catch (IOException e) {
			logger.warning("Failed to answer IPC request: " + e.getMessage());
		}
	}

	private void writeFrame(JsonObject frame) throws IOException {
		synchronized (writeLock) {
			Writer target = this.writer;
			if (target == null) throw new IOException("Connector IPC is not connected");
			target.write(frame.toString());
			target.write('\n');
			target.flush();
		}
	}

	private void failPending(String reason) {
		for (Map.Entry<String, CompletableFuture<
			JsonObject
		>> entry : pending.entrySet()) {
			entry.getValue().completeExceptionally(new IOException(reason));
		}
		pending.clear();
	}

	/** Reads an optional string field; {@code null} when absent or JSON null. */
	private static String optString(JsonObject json, String key) {
		return json.has(key) && !json.get(key).isJsonNull()
			? json.get(key).getAsString()
			: null;
	}

	@Override
	public void close() {
		running = false;
		SocketChannel open = this.channel;
		if (open != null) {
			try {
				open.close();
			} catch (IOException ignored) {
				// Closing is best-effort; the reader thread exits either way.
			}
		}
		if (readerThread != null) readerThread.interrupt();
		failPending("plugin shutting down");
		workers.shutdownNow();
	}
}

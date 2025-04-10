package io.github.ariuan.connectorPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.iki.elonen.NanoHTTPD;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class HttpServer extends NanoHTTPD {
    private LogCaptureHandler logCaptureHandler;

    public HttpServer(int port, LogCaptureHandler logCaptureHandler) throws IOException {
        super(port);
        this.logCaptureHandler = logCaptureHandler;
        start(SOCKET_READ_TIMEOUT, false);
        System.out.println("HTTP Server started on port " + port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (Method.POST.equals(session.getMethod())) {
                String uri = session.getUri();
                Map<String, String> body = new HashMap<>();
                session.parseBody(body);
                String rawBody = body.get("postData");
                JsonObject json = JsonParser.parseString(rawBody).getAsJsonObject();
                switch (uri) {
                    case "/runCommand": {
                        String command = json.get("command").getAsString();
                        Bukkit.getLogger().info("Called command: " + command);
                        if (command == null) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad Request, Missing command");
                        }
                        CompletableFuture<String> future = new CompletableFuture<>();

                        Bukkit.getScheduler().runTask(ConnectorPlugin.getInstance(), () -> {
                            CapturingConsoleSender sender = new CapturingConsoleSender();
                            LogCapture logCapture = new LogCapture();
                            Logger logger = Bukkit.getLogger();
                            logger.addHandler(logCapture);
                            boolean success = Bukkit.dispatchCommand(sender, command);

                            // Some commands log later, so wait 2 ticks
                            Bukkit.getScheduler().runTaskLater(ConnectorPlugin.getInstance(), () -> {
                                String output = sender.getOutput();
                                String loggerOutput = logCapture.getCapturedOutput();
                                logger.removeHandler(logCapture);
                                JsonObject response = new JsonObject();
                                response.addProperty("success", success);
                                response.addProperty("output", output.trim());
                                response.addProperty("logger", loggerOutput.trim());
                                future.complete(new Gson().toJson(response));
                            }, 2L);
                        });
                        String result = future.get(); // Safe because it's not the main thread
                        return newFixedLengthResponse(Response.Status.OK, "application/json", result);
                    }
                    case "/shutdown": {
                        long tickDelay = json.get("tick").getAsLong();
                        boolean successful = ConnectorPlugin.getInstance().shutdown(tickDelay);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                }
            } else if (Method.GET.equals(session.getMethod())) {
                String uri = session.getUri();
                switch (uri) {
                    case "/logs": {
                        JsonArray arr = getJsonArray();
                        Bukkit.getLogger().info("Called logs");
                        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
                    }
                    case "/players": {
                        JsonArray arr = new JsonArray();
                        Bukkit.getOnlinePlayers().forEach(player -> {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("uuid", player.getUniqueId().toString());
                            obj.addProperty("name", player.getName());
                            arr.add(obj);
                        });
                        return newFixedLengthResponse(Response.Status.OK, "application/json", arr.toString());
                    }
                    case "/cancelShutdown": {
                        boolean successful = ConnectorPlugin.getInstance().cancelShutdown();
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/shutdown": {
                        boolean successful = ConnectorPlugin.getInstance().shutdown(20 * 60);
                        JsonObject response = new JsonObject();
                        response.addProperty("success", successful);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/shuttingDown": {
                        boolean shuttingDown = ConnectorPlugin.getInstance().haveScheduledShutdown();
                        JsonObject response = new JsonObject();
                        response.addProperty("result", shuttingDown);
                        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString());
                    }
                    case "/ping": {
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Pong!");
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error");
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
    }

    private @NotNull JsonArray getJsonArray() {
        LogCaptureHandler.LogEntry[] entries = logCaptureHandler.getRecentLogs();
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

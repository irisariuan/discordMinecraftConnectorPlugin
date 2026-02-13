package io.github.ariuan.connectorPlugin;

import java.io.*;
import java.util.logging.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.time.Instant;

import com.google.gson.*;

public class LogCaptureHandler extends Handler {
    private static final int MAX_LOGS = 1000;
    private final LinkedBlockingQueue<LogEntry> logs = new LinkedBlockingQueue<>(MAX_LOGS);
    private final File logFile;
    private final Gson gson = new Gson();

    public static class LogEntry {
        public final String message;
        public final String timestamp;

        public LogEntry(String message) {
            this.message = message;
            this.timestamp = Instant.now().toString(); // ISO-8601
        }
    }

    public LogCaptureHandler(File logFile) {
        this.logFile = logFile;
        loadLogsFromFile();
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) return;

        String msg = getFormatter().formatMessage(record);
        LogEntry entry = new LogEntry(msg);

        // Keep in memory
        if (logs.size() == MAX_LOGS) logs.poll();
        logs.offer(entry);

        // Write to file
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(gson.toJson(entry));
            writer.write("\n"); // JSON Lines format
        } catch (IOException e) {
            ConnectorPlugin.getInstance().getLogger().warning("Error writing log: " + e.getMessage());
        }
    }

    public LogEntry[] getRecentLogs() {
        return logs.toArray(new LogEntry[logs.size()]);
    }

    private void loadLogsFromFile() {
        if (!logFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    LogEntry entry = gson.fromJson(line, LogEntry.class);
                    if (logs.size() == MAX_LOGS) logs.poll();
                    logs.offer(entry);
                } catch (JsonSyntaxException ignored) {
                }
            }
        } catch (IOException e) {
            ConnectorPlugin.getInstance().getLogger().warning("Error writing log: " + e.getMessage());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }
}
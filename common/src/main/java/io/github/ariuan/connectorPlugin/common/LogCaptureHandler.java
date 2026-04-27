package io.github.ariuan.connectorPlugin.common;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.*;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Persistent ring-buffer log handler shared by all platforms. */
public class LogCaptureHandler extends Handler {
    private static final int MAX_LOGS = 1000;
    private final LinkedBlockingQueue<LogEntry> logs = new LinkedBlockingQueue<>(MAX_LOGS);
    private final File logFile;
    private final Gson gson = new Gson();
    private final Logger platformLogger;

    public static class LogEntry {
        public final String message;
        public final String timestamp;

        public LogEntry(String message) {
            this.message = message;
            this.timestamp = Instant.now().toString();
        }
    }

    public LogCaptureHandler(File logFile, Logger platformLogger) {
        this.logFile = logFile;
        this.platformLogger = platformLogger;
        loadLogsFromFile();
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) return;

        String msg = getFormatter().formatMessage(record);
        LogEntry entry = new LogEntry(msg);

        if (logs.size() == MAX_LOGS) logs.poll();
        logs.offer(entry);

        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(gson.toJson(entry));
            writer.write("\n");
        } catch (IOException e) {
            platformLogger.warning("Error writing log: " + e.getMessage());
        }
    }

    public LogEntry[] getRecentLogs() {
        return logs.toArray(new LogEntry[0]);
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
            platformLogger.warning("Error loading logs: " + e.getMessage());
        }
    }

    @Override public void flush() {}
    @Override public void close() throws SecurityException {}
}

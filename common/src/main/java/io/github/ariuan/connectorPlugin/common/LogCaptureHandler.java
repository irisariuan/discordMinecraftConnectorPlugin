package io.github.ariuan.connectorPlugin.common;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Persistent ring-buffer log handler shared by all platforms. */
public class LogCaptureHandler extends Handler {
    private static final int MAX_LOGS = 1000;
    private final LinkedBlockingQueue<LogEntry> logs = new LinkedBlockingQueue<>(MAX_LOGS);
    private final Gson gson = new Gson();
    private final Logger platformLogger;
    private final Object writeLock = new Object();
    private Writer writer;

    public static class LogEntry {
        public final String message;
        public final String timestamp;

        public LogEntry(String message) {
            this.message = message;
            this.timestamp = Instant.now().toString();
        }
    }

    public LogCaptureHandler(File logFile, Logger platformLogger) {
        this.platformLogger = platformLogger;
        loadLogsFromFile(logFile);
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8));
        } catch (IOException e) {
            platformLogger.warning("Error opening log file: " + e.getMessage());
        }
    }

    @Override
    public void publish(LogRecord record) {
        if (!isLoggable(record)) return;

        LogEntry entry = new LogEntry(formatMessage(record));
        append(entry);

        synchronized (writeLock) {
            if (writer == null) return;
            try {
                writer.write(gson.toJson(entry));
                writer.write("\n");
                writer.flush();
            } catch (IOException e) {
                // Reporting through platformLogger here would re-enter publish() and
                // recurse until the stack overflows, since this handler is attached
                // to that logger.
                reportError("Error writing log", e, ErrorManager.WRITE_FAILURE);
            }
        }
    }

    /** Formats a record, tolerating a handler that was never given a formatter. */
    private String formatMessage(LogRecord record) {
        Formatter formatter = getFormatter();
        return formatter != null ? formatter.formatMessage(record) : record.getMessage();
    }

    /** Adds an entry, evicting the oldest ones while the buffer is at capacity. */
    private void append(LogEntry entry) {
        while (!logs.offer(entry)) {
            if (logs.poll() == null) break;
        }
    }

    public LogEntry[] getRecentLogs() {
        return logs.toArray(new LogEntry[0]);
    }

    private void loadLogsFromFile(File logFile) {
        if (!logFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(logFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    LogEntry entry = gson.fromJson(line, LogEntry.class);
                    // Blank or "null" lines parse to null, which the queue rejects.
                    if (entry != null) append(entry);
                } catch (JsonSyntaxException ignored) {
                }
            }
        } catch (IOException e) {
            platformLogger.warning("Error loading logs: " + e.getMessage());
        }
    }

    @Override
    public void flush() {
        synchronized (writeLock) {
            if (writer == null) return;
            try {
                writer.flush();
            } catch (IOException e) {
                reportError("Error flushing log", e, ErrorManager.FLUSH_FAILURE);
            }
        }
    }

    @Override
    public void close() throws SecurityException {
        synchronized (writeLock) {
            if (writer == null) return;
            try {
                writer.close();
            } catch (IOException e) {
                reportError("Error closing log", e, ErrorManager.CLOSE_FAILURE);
            } finally {
                writer = null;
            }
        }
    }
}

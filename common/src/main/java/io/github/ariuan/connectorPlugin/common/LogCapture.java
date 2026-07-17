package io.github.ariuan.connectorPlugin.common;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** Temporary per-command log handler that accumulates log output. */
public class LogCapture extends Handler {
    // Records arrive on whichever thread emitted them, while the captured text is
    // read from the thread that dispatched the command.
    private final StringBuffer captured = new StringBuffer();

    public String getCapturedOutput() {
        return captured.toString();
    }

    @Override
    public void publish(LogRecord record) {
        if (record != null && record.getMessage() != null) {
            captured.append(record.getMessage()).append("\n");
        }
    }

    @Override public void flush() {}
    @Override public void close() throws SecurityException {}
}

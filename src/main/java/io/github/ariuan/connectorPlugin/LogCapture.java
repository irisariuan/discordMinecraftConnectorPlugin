package io.github.ariuan.connectorPlugin;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

public class LogCapture extends Handler {
    private final StringBuilder captured = new StringBuilder();

    public String getCapturedOutput() {
        return captured.toString();
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getMessage() != null) {
            captured.append(record.getMessage()).append("\n");
        }
    }

    @Override public void flush() {}
    @Override public void close() throws SecurityException {}
}

package io.github.ariuan.connectorPlugin.common;

/** Result of {@link IPlatformAdapter#runCommandAndCapture(String)}. */
public class CommandResult {
    public final boolean success;
    public final String output;
    public final String loggerOutput;

    public CommandResult(boolean success, String output, String loggerOutput) {
        this.success = success;
        this.output = output;
        this.loggerOutput = loggerOutput;
    }
}

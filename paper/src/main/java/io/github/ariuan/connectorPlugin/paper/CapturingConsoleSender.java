package io.github.ariuan.connectorPlugin.paper;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Captures the text output produced by a dispatched command.
 *
 * <p>Uses {@link Bukkit#createCommandSender(java.util.function.Consumer)} so
 * no deprecated {@code ConsoleCommandSender} / {@code Conversable} interface
 * needs to be implemented. The returned sender has the same effective
 * permissions as the real console sender.</p>
 *
 * <p>Usage:
 * <pre>{@code
 *   CapturingConsoleSender cap = new CapturingConsoleSender();
 *   Bukkit.dispatchCommand(cap.asSender(), "say hello");
 *   String output = cap.getOutput();
 * }</pre>
 * </p>
 */
public final class CapturingConsoleSender {

	private final StringBuilder output = new StringBuilder();
	private final CommandSender sender;

	public CapturingConsoleSender() {
		this.sender = Bukkit.createCommandSender(component ->
			output
				.append(
					PlainTextComponentSerializer.plainText().serialize(
						component
					)
				)
				.append('\n')
		);
	}

	/**
	 * Returns the underlying {@link CommandSender} to pass to
	 * {@link Bukkit#dispatchCommand(CommandSender, String)}.
	 */
	public CommandSender asSender() {
		return sender;
	}

	/**
	 * Returns everything sent to this sender since construction,
	 * with each message on its own line.
	 */
	public String getOutput() {
		return output.toString();
	}
}

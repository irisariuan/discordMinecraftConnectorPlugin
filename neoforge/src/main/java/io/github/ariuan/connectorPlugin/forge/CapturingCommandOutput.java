package io.github.ariuan.connectorPlugin.forge;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

/** Captures command output written to a {@link CommandSource}. */
public class CapturingCommandOutput implements CommandSource {

	private final StringBuilder sb = new StringBuilder();

	public String getOutput() {
		return sb.toString();
	}

	@Override
	public void sendSystemMessage(Component message) {
		sb.append(message.getString()).append("\n");
	}

	@Override
	public boolean acceptsSuccess() {
		return true;
	}

	@Override
	public boolean acceptsFailure() {
		return true;
	}

	@Override
	public boolean shouldInformAdmins() {
		return false;
	}
}

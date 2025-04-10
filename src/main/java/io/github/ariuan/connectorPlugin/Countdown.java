package io.github.ariuan.connectorPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;

public class Countdown {
    private int countdownTimer;

    public void start(final int time)
    {
        this.countdownTimer = Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(ConnectorPlugin.getInstance(), new Runnable()
                {
                    int i = time;

                    public void run()
                    {
                        Bukkit.broadcast(Component.text("Shutting down in " + i + " seconds!", NamedTextColor.DARK_RED));
                        this.i--;
                        if (this.i <= 0)
                        {
                            cancel();
                            //ended
                        }
                    }
                }
                , 0L, 20L);
    }

    public void cancel()
    {
        Bukkit.getScheduler().cancelTask(this.countdownTimer);
    }
}

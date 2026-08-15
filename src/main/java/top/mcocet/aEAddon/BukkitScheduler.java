package top.mcocet.aEAddon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class BukkitScheduler implements Scheduler {
    
    private final JavaPlugin plugin;
    
    public BukkitScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public void runTask(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
    
    @Override
    public void runTaskLater(Runnable task, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }
    
    @Override
    public void runTaskTimer(Runnable task, long delay, long period) {
        Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
    }
    
    @Override
    public void runTaskAsync(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }
    
    @Override
    public void runTaskLaterAsync(Runnable task, long delay) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
    }
    
    @Override
    public void executePlayerCommand(Player player, String command) {
        Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(command));
    }
}

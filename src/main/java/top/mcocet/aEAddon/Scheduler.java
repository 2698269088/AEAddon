package top.mcocet.aEAddon;

import org.bukkit.entity.Player;

public interface Scheduler {
    
    void runTask(Runnable task);
    
    void runTaskLater(Runnable task, long delay);
    
    void runTaskTimer(Runnable task, long delay, long period);
    
    void runTaskAsync(Runnable task);
    
    void runTaskLaterAsync(Runnable task, long delay);
    
    void executePlayerCommand(Player player, String command);
}

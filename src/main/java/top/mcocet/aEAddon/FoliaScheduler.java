package top.mcocet.aEAddon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.logging.Level;

public class FoliaScheduler implements Scheduler {
    
    private final JavaPlugin plugin;
    private Object globalRegionScheduler;
    private Object asyncScheduler;
    private Method executeMethod;
    private Method runDelayedMethod;
    private Method runAtFixedRateMethod;
    private Method runNowMethod;
    private Method runDelayedAsyncMethod;
    private Method runAtFixedRateAsyncMethod;
    private Class<?> scheduledTaskClass;
    
    public FoliaScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
        try {
            // 获取Folia的调度器
            globalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            asyncScheduler = Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
            
            Class<?> schedulerClass = globalRegionScheduler.getClass();
            // Folia 使用 Consumer<ScheduledTask> 而不是 Runnable
            scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            
            // execute(Plugin, Runnable)
            executeMethod = schedulerClass.getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class);
            // runDelayed(Plugin, Consumer<ScheduledTask>, long)
            runDelayedMethod = schedulerClass.getMethod("runDelayed", org.bukkit.plugin.Plugin.class, Consumer.class, long.class);
            // runAtFixedRate(Plugin, Consumer<ScheduledTask>, long, long)
            runAtFixedRateMethod = schedulerClass.getMethod("runAtFixedRate", org.bukkit.plugin.Plugin.class, Consumer.class, long.class, long.class);
            
            Class<?> asyncSchedulerClass = asyncScheduler.getClass();
            // runNow(Plugin, Consumer<ScheduledTask>)
            runNowMethod = asyncSchedulerClass.getMethod("runNow", org.bukkit.plugin.Plugin.class, Consumer.class);
            // runDelayed(Plugin, Consumer<ScheduledTask>, long, TimeUnit)
            runDelayedAsyncMethod = asyncSchedulerClass.getMethod("runDelayed", org.bukkit.plugin.Plugin.class, Consumer.class, long.class, java.util.concurrent.TimeUnit.class);
            // runAtFixedRate(Plugin, Consumer<ScheduledTask>, long, long, TimeUnit)
            runAtFixedRateAsyncMethod = asyncSchedulerClass.getMethod("runAtFixedRate", org.bukkit.plugin.Plugin.class, Consumer.class, long.class, long.class, java.util.concurrent.TimeUnit.class);
            
            plugin.getLogger().info("Folia调度器初始化成功");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "无法初始化Folia调度器: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void runTask(Runnable task) {
        try {
            if (executeMethod != null) {
                executeMethod.invoke(globalRegionScheduler, plugin, task);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "runTask failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void runTaskLater(Runnable task, long delay) {
        try {
            if (runDelayedMethod != null) {
                Consumer<Object> consumer = scheduledTask -> task.run();
                runDelayedMethod.invoke(globalRegionScheduler, plugin, consumer, delay);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "runTaskLater failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void runTaskTimer(Runnable task, long delay, long period) {
        try {
            if (runAtFixedRateMethod != null) {
                Consumer<Object> consumer = scheduledTask -> task.run();
                runAtFixedRateMethod.invoke(globalRegionScheduler, plugin, consumer, delay, period);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "runTaskTimer failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void runTaskAsync(Runnable task) {
        try {
            if (runNowMethod != null) {
                Consumer<Object> consumer = scheduledTask -> task.run();
                runNowMethod.invoke(asyncScheduler, plugin, consumer);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "runTaskAsync failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void runTaskLaterAsync(Runnable task, long delay) {
        try {
            if (runDelayedAsyncMethod != null) {
                Consumer<Object> consumer = scheduledTask -> task.run();
                runDelayedAsyncMethod.invoke(asyncScheduler, plugin, consumer, delay * 50, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "runTaskLaterAsync failed: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void executePlayerCommand(Player player, String command) {
        try {
            // 在Folia中，玩家命令需要在玩家所在区域的调度器上执行
            Object regionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
            Method executeMethod = regionScheduler.getClass().getMethod("execute", org.bukkit.plugin.Plugin.class, org.bukkit.Location.class, Runnable.class);
            Runnable task = new Runnable() {
                @Override
                public void run() {
                    player.performCommand(command);
                }
            };
            executeMethod.invoke(regionScheduler, plugin, player.getLocation(), task);
        } catch (Exception e) {
            // 回退到全局调度器
            runTask(new Runnable() {
                @Override
                public void run() {
                    player.performCommand(command);
                }
            });
        }
    }
}

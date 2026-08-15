package top.mcocet.aEAddon.foliafix;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Folia-compatible replacement for Bukkit.getScheduler() calls inside AdvancedEnchantments.
 *
 * This class is called by bytecode-patched AE classes instead of Bukkit.getScheduler().
 * It routes AE's scheduler calls to the correct Folia scheduler without affecting
 * any other plugin.
 */
public class AEFoliaSchedulerHelper {

    private static final Logger LOGGER = Logger.getLogger("AEAddon-FoliaFix");

    private static final boolean isFolia = checkFolia();

    // Folia scheduler instances and methods (reflection, so this compiles on Paper too)
    private static Object globalRegionScheduler = null;
    private static Object asyncScheduler = null;
    private static Method globalExecute = null;
    private static Method globalRunDelayed = null;
    private static Method globalRunAtFixedRate = null;
    private static Method globalCancelTasks = null;
    private static Method asyncRunNow = null;
    private static Method asyncRunDelayed = null;
    private static Method asyncRunAtFixedRate = null;
    private static Method asyncCancelTasks = null;
    private static Method scheduledTaskCancel = null;

    // Task ID management for BukkitTask compatibility
    private static final int ID_START = 1_000_000;
    private static final AtomicInteger nextId = new AtomicInteger(ID_START);
    private static final Map<Integer, Object> foliaTasks = new ConcurrentHashMap<>();

    // Cached BukkitScheduler proxy used by bytecode-patched AE classes.
    private static BukkitScheduler schedulerProxy;

    static {
        if (isFolia) {
            initFolia();
        }
    }

    /**
     * Returns a BukkitScheduler proxy that routes AE's scheduler calls through this helper.
     * This is returned by bytecode-patched AE classes in place of Bukkit.getScheduler().
     */
    public static BukkitScheduler getScheduler() {
        if (schedulerProxy == null) {
            schedulerProxy = (BukkitScheduler) Proxy.newProxyInstance(
                    BukkitScheduler.class.getClassLoader(),
                    new Class<?>[] { BukkitScheduler.class },
                    new SchedulerProxyHandler()
            );
        }
        return schedulerProxy;
    }

    private static boolean checkFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void initFolia() {
        try {
            Object server = Bukkit.getServer();
            Class<?> serverClass = server.getClass();

            globalRegionScheduler = serverClass.getMethod("getGlobalRegionScheduler").invoke(server);
            asyncScheduler = serverClass.getMethod("getAsyncScheduler").invoke(server);

            Class<?> pluginClass = Class.forName("org.bukkit.plugin.Plugin");
            Class<?> runnableClass = Runnable.class;
            Class<?> consumerClass = Consumer.class;

            Class<?> globalClass = globalRegionScheduler.getClass();
            globalExecute = globalClass.getMethod("execute", pluginClass, runnableClass);
            globalRunDelayed = globalClass.getMethod("runDelayed", pluginClass, consumerClass, long.class);
            globalRunAtFixedRate = globalClass.getMethod("runAtFixedRate", pluginClass, consumerClass, long.class, long.class);
            globalCancelTasks = globalClass.getMethod("cancelTasks", pluginClass);

            Class<?> asyncClass = asyncScheduler.getClass();
            asyncRunNow = asyncClass.getMethod("runNow", pluginClass, consumerClass);
            asyncRunDelayed = asyncClass.getMethod("runDelayed", pluginClass, consumerClass, long.class, TimeUnit.class);
            asyncRunAtFixedRate = asyncClass.getMethod("runAtFixedRate", pluginClass, consumerClass, long.class, long.class, TimeUnit.class);
            asyncCancelTasks = asyncClass.getMethod("cancelTasks", pluginClass);

            Class<?> scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            scheduledTaskCancel = scheduledTaskClass.getMethod("cancel");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[AEAddon-FoliaFix] Failed to initialize Folia scheduler helper: " + e.getMessage(), e);
        }
    }

    // ==================== Sync tasks ====================

    public static BukkitTask runTask(Plugin plugin, Runnable task) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTask(plugin, task);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            globalExecute.invoke(globalRegionScheduler, plugin, wrapped);
            BukkitTask bukkitTask = new DummyBukkitTask(plugin, nextId.getAndIncrement(), true);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTask", e);
            return null;
        }
    }

    public static BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = globalRunDelayed.invoke(globalRegionScheduler, plugin, (Consumer<Object>) t -> wrapped.run(), Math.max(1L, delay));
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, true, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskLater", e);
            return null;
        }
    }

    public static BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        }
        try {
            // If the task is a BukkitRunnable, we need to handle cancel() properly
            // by setting its internal task field so BukkitRunnable.cancel() works.
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = globalRunAtFixedRate.invoke(globalRegionScheduler, plugin, (Consumer<Object>) t -> wrapped.run(), Math.max(1L, delay), Math.max(1L, period));
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, true, foliaTask);
            taskHolder[0] = bukkitTask;
            // If it's a BukkitRunnable, inject the task so cancel() works
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskTimer", e);
            return null;
        }
    }

    // ==================== Async tasks ====================

    public static BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = asyncRunNow.invoke(asyncScheduler, plugin, (Consumer<Object>) t -> wrapped.run());
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskAsynchronously", e);
            return null;
        }
    }

    public static BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = asyncRunDelayed.invoke(asyncScheduler, plugin, (Consumer<Object>) t -> wrapped.run(), Math.max(1L, delay) * 50L, TimeUnit.MILLISECONDS);
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskLaterAsynchronously", e);
            return null;
        }
    }

    public static BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = asyncRunAtFixedRate.invoke(asyncScheduler, plugin, (Consumer<Object>) t -> wrapped.run(), Math.max(1L, delay) * 50L, Math.max(1L, period) * 50L, TimeUnit.MILLISECONDS);
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskTimerAsynchronously", e);
            return null;
        }
    }

    // ==================== BukkitRunnable convenience overloads ====================
    // These are used by bytecode-patched AE code that calls BukkitRunnable.runXxx().

    public static BukkitTask runTask(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin) {
        return runTask(plugin, runnable);
    }

    public static BukkitTask runTaskLater(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin, long delay) {
        return runTaskLater(plugin, runnable, delay);
    }

    public static BukkitTask runTaskTimer(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin, long delay, long period) {
        return runTaskTimer(plugin, runnable, delay, period);
    }

    public static BukkitTask runTaskAsynchronously(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin) {
        return runTaskAsynchronously(plugin, runnable);
    }

    public static BukkitTask runTaskLaterAsynchronously(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin, long delay) {
        return runTaskLaterAsynchronously(plugin, runnable, delay);
    }

    public static BukkitTask runTaskTimerAsynchronously(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, runnable, delay, period);
    }

    // ==================== Legacy schedule methods ====================

    public static int scheduleSyncDelayedTask(Plugin plugin, Runnable task) {
        BukkitTask t = runTaskLater(plugin, task, 1L);
        return t != null ? t.getTaskId() : -1;
    }

    public static int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        BukkitTask t = runTaskLater(plugin, task, delay);
        return t != null ? t.getTaskId() : -1;
    }

    // ==================== Cancellation ====================

    public static void cancelTasks(Plugin plugin) {
        if (!isFolia) {
            Bukkit.getScheduler().cancelTasks(plugin);
            return;
        }
        try {
            if (globalCancelTasks != null) globalCancelTasks.invoke(globalRegionScheduler, plugin);
            if (asyncCancelTasks != null) asyncCancelTasks.invoke(asyncScheduler, plugin);
            foliaTasks.clear();
        } catch (Exception e) {
            logException(plugin, "cancelTasks", e);
        }
    }

    public static void cancelTask(int taskId) {
        if (!isFolia) {
            Bukkit.getScheduler().cancelTask(taskId);
            return;
        }
        Object foliaTask = foliaTasks.remove(taskId);
        if (foliaTask != null && scheduledTaskCancel != null) {
            try {
                scheduledTaskCancel.invoke(foliaTask);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to cancel Folia task: " + e.getMessage(), e);
            }
        }
    }

    // ==================== Utility ====================

    /**
     * Wraps a Runnable with exception handling.
     * If taskHolder is provided and the task throws, the holder's task will be cancelled.
     */
    private static Runnable wrap(Plugin plugin, Runnable task, BukkitTask[] taskHolder) {
        return () -> {
            try {
                task.run();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Task exception in AE Folia wrapper", e);
                if (taskHolder != null && taskHolder[0] != null) {
                    try {
                        taskHolder[0].cancel();
                    } catch (UnsupportedOperationException ex) {
                        // Folia doesn't support CraftScheduler.cancelTask
                        // Cancel the underlying Folia task directly
                        if (taskHolder[0] instanceof FoliaBukkitTask) {
                            ((FoliaBukkitTask) taskHolder[0]).cancelDirect();
                        }
                    }
                }
            }
        };
    }

    private static Runnable wrap(Plugin plugin, Runnable task) {
        return wrap(plugin, task, null);
    }

    /**
     * Injects a BukkitTask into a BukkitRunnable via reflection so that
     * BukkitRunnable.cancel() / getTaskId() work correctly on Folia.
     */
    private static void injectBukkitRunnableTask(org.bukkit.scheduler.BukkitRunnable runnable, BukkitTask task) {
        try {
            java.lang.reflect.Field taskField = org.bukkit.scheduler.BukkitRunnable.class.getDeclaredField("task");
            taskField.setAccessible(true);
            taskField.set(runnable, task);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[AEAddon-FoliaFix] Failed to inject task into BukkitRunnable: " + e.getMessage());
        }
    }

    private static void logException(Plugin plugin, String method, Exception e) {
        plugin.getLogger().warning("[AEAddon-FoliaFix] " + method + " failed: " + e.getMessage());
        LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] " + method + " exception", e);
    }

    /**
     * 安全取消 BukkitRunnable，避免 Folia 上的 UnsupportedOperationException
     */
    public static void cancelBukkitRunnable(org.bukkit.scheduler.BukkitRunnable runnable) {
        try {
            // 尝试通过反射获取内部的 task 字段并取消
            java.lang.reflect.Field taskField = org.bukkit.scheduler.BukkitRunnable.class.getDeclaredField("task");
            taskField.setAccessible(true);
            Object task = taskField.get(runnable);
            if (task instanceof BukkitTask) {
                BukkitTask bukkitTask = (BukkitTask) task;
                // 如果是我们的 FoliaBukkitTask，直接调用 cancelDirect
                if (bukkitTask instanceof FoliaBukkitTask) {
                    ((FoliaBukkitTask) bukkitTask).cancelDirect();
                } else {
                    bukkitTask.cancel();
                }
            }
        } catch (Exception e) {
            // 如果反射失败，尝试直接取消
            try {
                runnable.cancel();
            } catch (UnsupportedOperationException ex) {
                // 忽略 Folia 上的取消错误
            }
        }
    }

    // ==================== BukkitScheduler proxy ====================

    private static class SchedulerProxyHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args == null) {
                args = new Object[0];
            }

            String name = method.getName();
            switch (name) {
                case "runTask":
                    return runTask((Plugin) args[0], castRunnable(args[1]));
                case "runTaskLater":
                    return runTaskLater((Plugin) args[0], castRunnable(args[1]), castLong(args[2]));
                case "runTaskTimer":
                    return runTaskTimer((Plugin) args[0], castRunnable(args[1]), castLong(args[2]), castLong(args[3]));
                case "runTaskAsynchronously":
                    return runTaskAsynchronously((Plugin) args[0], castRunnable(args[1]));
                case "runTaskLaterAsynchronously":
                    return runTaskLaterAsynchronously((Plugin) args[0], castRunnable(args[1]), castLong(args[2]));
                case "runTaskTimerAsynchronously":
                    return runTaskTimerAsynchronously((Plugin) args[0], castRunnable(args[1]), castLong(args[2]), castLong(args[3]));
                case "scheduleSyncDelayedTask":
                    if (args.length == 2) {
                        return scheduleSyncDelayedTask((Plugin) args[0], castRunnable(args[1]));
                    }
                    return scheduleSyncDelayedTask((Plugin) args[0], castRunnable(args[1]), castLong(args[2]));
                case "scheduleAsyncDelayedTask":
                case "scheduleAsyncRepeatingTask":
                case "scheduleSyncRepeatingTask":
                    throw new UnsupportedOperationException(name + " is not supported by AE Folia helper");
                case "cancelTasks":
                    cancelTasks((Plugin) args[0]);
                    return null;
                case "cancelTask":
                    cancelTask((Integer) args[0]);
                    return null;
                case "isCurrentlyRunning":
                case "isQueued":
                    return false;
                case "getActiveWorkers":
                case "getPendingTasks":
                    return Collections.emptyList();
                default:
                    throw new UnsupportedOperationException(name + " is not supported by AE Folia helper");
            }
        }

        private static Runnable castRunnable(Object obj) {
            if (obj instanceof Runnable) {
                return (Runnable) obj;
            }
            throw new IllegalArgumentException("Expected Runnable, got " + (obj == null ? "null" : obj.getClass()));
        }

        private static long castLong(Object obj) {
            if (obj instanceof Long) return (Long) obj;
            if (obj instanceof Integer) return ((Integer) obj).longValue();
            throw new IllegalArgumentException("Expected long, got " + (obj == null ? "null" : obj.getClass()));
        }
    }

    // ==================== BukkitTask implementations ====================

    private static class FoliaBukkitTask implements BukkitTask {
        private final Plugin plugin;
        private final int taskId;
        private final boolean sync;
        private final Object foliaTask;
        private volatile boolean cancelled = false;

        FoliaBukkitTask(Plugin plugin, int taskId, boolean sync, Object foliaTask) {
            this.plugin = plugin;
            this.taskId = taskId;
            this.sync = sync;
            this.foliaTask = foliaTask;
        }

        @Override
        public int getTaskId() {
            return taskId;
        }

        @Override
        public Plugin getOwner() {
            return plugin;
        }

        @Override
        public boolean isSync() {
            return sync;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
            cancelTask(taskId);
        }

        public void cancelDirect() {
            if (scheduledTaskCancel != null) {
                try {
                    scheduledTaskCancel.invoke(foliaTask);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to cancel Folia task: " + e.getMessage(), e);
                }
            }
        }
    }

    private static class DummyBukkitTask implements BukkitTask {
        private final Plugin plugin;
        private final int taskId;
        private final boolean sync;
        private volatile boolean cancelled = false;

        DummyBukkitTask(Plugin plugin, int taskId, boolean sync) {
            this.plugin = plugin;
            this.taskId = taskId;
            this.sync = sync;
        }

        @Override
        public int getTaskId() {
            return taskId;
        }

        @Override
        public Plugin getOwner() {
            return plugin;
        }

        @Override
        public boolean isSync() {
            return sync;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}

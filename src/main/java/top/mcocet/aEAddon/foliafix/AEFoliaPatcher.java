package top.mcocet.aEAddon.foliafix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Runtime patcher for AdvancedEnchantments FoliaScheduler.
 * 
 * This class uses reflection to redirect AE's FoliaScheduler calls from
 * GlobalRegionScheduler to RegionScheduler, fixing the thread safety issue
 * when applying potion effects to entities.
 */
public class AEFoliaPatcher {

    private static boolean patched = false;
    private static JavaPlugin plugin;
    
    // Folia scheduler instances
    private static Object globalRegionScheduler;
    private static Object regionScheduler;
    private static Method regionExecuteMethod;
    private static Method regionRunDelayedMethod;
    private static Method regionRunAtFixedRateMethod;
    
    public static void init(JavaPlugin pluginInstance) {
        if (patched) return;
        plugin = pluginInstance;
        
        try {
            // Check if we're on Folia
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            
            // Get scheduler instances
            globalRegionScheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            regionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
            
            Class<?> regionSchedulerClass = regionScheduler.getClass();
            regionExecuteMethod = regionSchedulerClass.getMethod("execute", Plugin.class, Location.class, Runnable.class);
            regionRunDelayedMethod = regionSchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class, Location.class);
            regionRunAtFixedRateMethod = regionSchedulerClass.getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, Location.class);
            
            plugin.getLogger().info("[AEAddon-FoliaFix] RegionScheduler methods acquired, ready to patch");
            
        } catch (ClassNotFoundException e) {
            plugin.getLogger().info("[AEAddon-FoliaFix] Not on Folia, no patching needed");
            return;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[AEAddon-FoliaFix] Failed to initialize patcher: " + e.getMessage(), e);
            return;
        }
        
        patched = true;
    }
    
    /**
     * Execute a task on the region scheduler at the entity's location.
     * This should be used instead of GlobalRegionScheduler.execute() when
     * the task involves entity operations.
     */
    public static void executeOnEntityRegion(Entity entity, Runnable task) {
        if (!patched || regionScheduler == null || regionExecuteMethod == null) {
            task.run();
            return;
        }
        
        try {
            // Check if we're already on the correct thread
            if (isOnEntityThread(entity)) {
                task.run();
                return;
            }
            
            regionExecuteMethod.invoke(regionScheduler, plugin, entity.getLocation(), task);
        } catch (Exception e) {
            plugin.getLogger().warning("[AEAddon-FoliaFix] Failed to execute on region scheduler: " + e.getMessage());
            task.run(); // Fallback
        }
    }
    
    /**
     * Check if current thread is the entity's region thread.
     */
    private static boolean isOnEntityThread(Entity entity) {
        try {
            Method isOwnedMethod = entity.getClass().getMethod("isOwnedByCurrentRegion");
            return (boolean) isOwnedMethod.invoke(entity);
        } catch (Exception e) {
            return false;
        }
    }
    
    public static boolean isPatched() {
        return patched;
    }
}

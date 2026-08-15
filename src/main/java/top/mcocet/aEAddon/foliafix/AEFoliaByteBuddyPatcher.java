package top.mcocet.aEAddon.foliafix;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * ByteBuddy-based runtime patcher for AdvancedEnchantments.
 * 
 * This class uses ByteBuddy to dynamically modify AE's effect classes
 * to execute entity operations on the correct Folia scheduler thread.
 * 
 * Unlike Java Agent, this works at runtime without requiring -javaagent JVM argument.
 */
public class AEFoliaByteBuddyPatcher {

    private static boolean patched = false;
    private static JavaPlugin plugin;
    private static Instrumentation instrumentation;
    private static ApplyPotionEffectTransformer transformer;
    
    // Folia scheduler instances (initialized via reflection)
    private static Object regionScheduler;
    private static Method regionExecuteMethod;
    private static boolean isFolia = false;

    /**
     * Initialize and apply the patch.
     * This should be called from AEAddon's onEnable.
     */
    public static void init(JavaPlugin pluginInstance) {
        if (patched) {
            pluginInstance.getLogger().info("[AEFoliaFix] Patch already applied, skipping");
            return;
        }
        
        plugin = pluginInstance;
        
        // Check if we're on Folia
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
            initFoliaScheduler();
        } catch (ClassNotFoundException e) {
            pluginInstance.getLogger().info("[AEFoliaFix] Not running on Folia, skipping patch");
            return;
        }
        
        try {
            applyPatch();
            patched = true;
            pluginInstance.getLogger().info("[AEFoliaFix] ByteBuddy patch applied successfully!");
        } catch (Exception e) {
            pluginInstance.getLogger().log(Level.SEVERE, "[AEFoliaFix] Failed to apply ByteBuddy patch: " + e.getMessage(), e);
        }
    }
    
    private static void initFoliaScheduler() {
        try {
            regionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
            regionExecuteMethod = regionScheduler.getClass().getMethod("execute", 
                org.bukkit.plugin.Plugin.class, org.bukkit.Location.class, Runnable.class);
        } catch (Exception e) {
            plugin.getLogger().warning("[AEFoliaFix] Failed to initialize Folia scheduler: " + e.getMessage());
        }
    }
    
    /**
     * Apply the ByteBuddy patch to AE's effect classes.
     * Uses getAllLoadedClasses() to avoid triggering Class.forName() which loads classes.
     */
    private static void applyPatch() throws Exception {
        // Get the Instrumentation instance
        instrumentation = ByteBuddyAgent.install();
        
        // Create a ClassFileTransformer using ASM
        transformer = new ApplyPotionEffectTransformer();
        ApplyPotionEffectTransformer.setPluginLogger(plugin.getLogger());
        instrumentation.addTransformer(transformer, true);
        
        // Collect target classes first to show progress
        List<Class<?>> targetClasses = new ArrayList<>();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String name = clazz.getName();
            if (name.startsWith("net.advancedplugins.ae.")) {
                targetClasses.add(clazz);
            }
        }
        
        int total = targetClasses.size();
        
        if (total > 0) {
            plugin.getLogger().info("[AEFoliaFix] Retransforming " + total + " AE classes...");
        }
        
        // OPTIMIZATION: Use single-threaded batch processing for better throughput
        // Multi-threading doesn't help much because JVM retransform locks internally
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger processedCount = new AtomicInteger(0);
        
        int batchSize = 100; // Larger batches = less JVM overhead
        int lastReportedPercent = -1;
        
        // Process in batches on a single thread for better cache locality
        for (int i = 0; i < targetClasses.size(); i += batchSize) {
            int batchEnd = Math.min(i + batchSize, targetClasses.size());
            List<Class<?>> batch = targetClasses.subList(i, batchEnd);
            
            try {
                // Batch retransform is most efficient
                Class<?>[] batchArray = batch.toArray(new Class<?>[0]);
                instrumentation.retransformClasses(batchArray);
                successCount.addAndGet(batch.size());
            } catch (Exception e) {
                // If batch fails, fall back to individual processing
                for (Class<?> clazz : batch) {
                    try {
                        instrumentation.retransformClasses(clazz);
                        successCount.incrementAndGet();
                    } catch (Exception ex) {
                        failCount.incrementAndGet();
                        // Only log non-Lambda failures
                        if (!clazz.getName().contains("$$Lambda")) {
                            plugin.getLogger().log(Level.WARNING, "[AEFoliaFix] Failed to retransform " + clazz.getName() + ": " + ex.getMessage());
                        }
                    }
                }
            }
            
            // Update progress
            processedCount.set(batchEnd);
            int percent = (int) ((double) batchEnd / total * 100);
            int tenPercent = (percent / 10) * 10; // Round down to nearest 10
            
            // Report at 10% intervals
            if (tenPercent > lastReportedPercent && tenPercent < 100) {
                lastReportedPercent = tenPercent;
                printProgressBar(batchEnd, total, successCount.get(), failCount.get(), tenPercent);
            }
        }
        
        // Final 100% report
        if (total > 0) {
            printProgressBar(total, total, successCount.get(), failCount.get(), 100);
            plugin.getLogger().info("[AEFoliaFix] Retransform complete: " + successCount.get() + " success, " + failCount.get() + " failed");
        }
        plugin.getLogger().info("[AEFoliaFix] Transformer stats: " + ApplyPotionEffectTransformer.getDebugStats());
    }
    
    private static void printProgressBar(int current, int total, int success, int failed, int percent) {
        int barWidth = 30;
        int filled = (int) ((double) current / total * barWidth);
        StringBuilder bar = new StringBuilder();
        bar.append('[');
        for (int i = 0; i < barWidth; i++) {
            bar.append(i < filled ? '=' : ' ');
        }
        bar.append(']');
        plugin.getLogger().info(String.format("[AEFoliaFix] %s %d%% (%d/%d, success=%d, failed=%d)", 
            bar.toString(), percent, current, total, success, failed));
    }
    
    /**
     * Retransform classes that have been loaded after initial patch.
     * Call this when AdvancedEnchantments plugin is enabled.
     * Uses getAllLoadedClasses() to avoid triggering class loading.
     */
    public static void retransformLoadedClasses(JavaPlugin pluginInstance) {
        if (!patched || instrumentation == null) {
            pluginInstance.getLogger().warning("[AEFoliaFix] Patch not initialized, cannot retransform");
            return;
        }
        
        // Collect target classes first to show progress
        List<Class<?>> targetClasses = new ArrayList<>();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String name = clazz.getName();
            if (name.startsWith("net.advancedplugins.ae.")) {
                targetClasses.add(clazz);
            }
        }
        
        int total = targetClasses.size();
        
        if (total > 0) {
            pluginInstance.getLogger().info("[AEFoliaFix] Retransforming " + total + " AE classes...");
        }
        
        // Single-threaded batch processing for better throughput
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        
        int batchSize = 100;
        int lastReportedPercent = -1;
        
        for (int i = 0; i < targetClasses.size(); i += batchSize) {
            int batchEnd = Math.min(i + batchSize, targetClasses.size());
            List<Class<?>> batch = targetClasses.subList(i, batchEnd);
            
            try {
                Class<?>[] batchArray = batch.toArray(new Class<?>[0]);
                instrumentation.retransformClasses(batchArray);
                successCount.addAndGet(batch.size());
            } catch (java.lang.InternalError e) {
                for (Class<?> clazz : batch) {
                    try {
                        instrumentation.retransformClasses(clazz);
                        successCount.incrementAndGet();
                    } catch (java.lang.InternalError ex) {
                        skipCount.incrementAndGet();
                    } catch (Exception ex) {
                        if (!clazz.getName().contains("$$Lambda")) {
                            pluginInstance.getLogger().warning("[AEFoliaFix] Failed to retransform " + clazz.getName() + ": " + ex.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                for (Class<?> clazz : batch) {
                    try {
                        instrumentation.retransformClasses(clazz);
                        successCount.incrementAndGet();
                    } catch (java.lang.InternalError ex) {
                        skipCount.incrementAndGet();
                    } catch (Exception ex) {
                        if (!clazz.getName().contains("$$Lambda")) {
                            pluginInstance.getLogger().warning("[AEFoliaFix] Failed to retransform " + clazz.getName() + ": " + ex.getMessage());
                        }
                    }
                }
            }
            
            // Report progress at 10% intervals
            int percent = (int) ((double) batchEnd / total * 100);
            int tenPercent = (percent / 10) * 10;
            if (tenPercent > lastReportedPercent && tenPercent < 100) {
                lastReportedPercent = tenPercent;
                printProgressBar(batchEnd, total, successCount.get(), skipCount.get(), tenPercent);
            }
        }
        
        if (total > 0) {
            printProgressBar(total, total, successCount.get(), skipCount.get(), 100);
            pluginInstance.getLogger().info("[AEFoliaFix] Retransform complete: " + successCount.get() + " success, " + skipCount.get() + " skipped");
        }
    }
    
    /**
     * This method is called by the transformed ApplyPotionEffect class.
     * It executes addPotionEffect on the correct Folia scheduler thread.
     */
    public static boolean addPotionEffect(LivingEntity entity, PotionEffect effect) {
        if (!isFolia || regionScheduler == null || regionExecuteMethod == null) {
            return entity.addPotionEffect(effect);
        }
        
        // Check if already on correct thread
        if (isOnEntityThread(entity)) {
            return entity.addPotionEffect(effect);
        }
        
        // Schedule on correct thread
        try {
            regionExecuteMethod.invoke(regionScheduler, plugin, entity.getLocation(), (Runnable) () -> {
                try {
                    entity.addPotionEffect(effect);
                } catch (Exception e) {
                    plugin.getLogger().warning("[AEFoliaFix] Failed to add potion effect: " + e.getMessage());
                }
            });
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[AEFoliaFix] Failed to schedule potion effect: " + e.getMessage());
            return entity.addPotionEffect(effect);
        }
    }
    
    /**
     * This method is called by the transformed ApplyPotionEffect class.
     * It executes removePotionEffect on the correct Folia scheduler thread.
     */
    public static void removePotionEffect(LivingEntity entity, PotionEffectType type) {
        if (!isFolia || regionScheduler == null || regionExecuteMethod == null) {
            entity.removePotionEffect(type);
            return;
        }
        
        if (isOnEntityThread(entity)) {
            entity.removePotionEffect(type);
            return;
        }
        
        try {
            regionExecuteMethod.invoke(regionScheduler, plugin, entity.getLocation(), (Runnable) () -> {
                try {
                    entity.removePotionEffect(type);
                } catch (Exception e) {
                    plugin.getLogger().warning("[AEFoliaFix] Failed to remove potion effect: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("[AEFoliaFix] Failed to schedule potion removal: " + e.getMessage());
            entity.removePotionEffect(type);
        }
    }
    
    private static boolean isOnEntityThread(Entity entity) {
        try {
            Method isOwnedMethod = entity.getClass().getMethod("isOwnedByCurrentRegion");
            return (boolean) isOwnedMethod.invoke(entity);
        } catch (Exception e) {
            return false;
        }
    }
}

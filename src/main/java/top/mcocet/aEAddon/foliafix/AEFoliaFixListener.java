package top.mcocet.aEAddon.foliafix;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Folia compatibility fix for AdvancedEnchantments.
 * 
 * This listener intercepts potion effect events and ensures they are applied
 * on the correct Folia scheduler thread to avoid "Cannot add effects to entities asynchronously" errors.
 * 
 * How it works:
 * 1. Tracks players who recently joined (AE applies effects on join)
 * 2. Intercepts EntityPotionEffectEvent when it's fired from wrong thread
 * 3. Re-schedules the effect application on the entity's correct region thread
 */
public class AEFoliaFixListener implements Listener {

    private final JavaPlugin plugin;
    private final boolean isFolia;
    
    // Track players who recently had armor checks (AE applies EFFECT_STATIC on these events)
    private final Map<UUID, Long> recentArmorCheckPlayers = new ConcurrentHashMap<>();
    
    // Track players who are being processed to avoid recursion
    private final Set<UUID> processingPlayers = ConcurrentHashMap.newKeySet();
    
    // Timeout for tracking (in milliseconds)
    private static final long TRACK_TIMEOUT = 2000; // 2 seconds
    
    public AEFoliaFixListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.isFolia = checkFolia();
        
        if (isFolia) {
            plugin.getLogger().info("[AEFoliaFix] Folia detected, enabling thread-safe potion effect handling");
            startCleanupTask();
        }
    }
    
    private boolean checkFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Track player joins - AE applies EFFECT_STATIC effects on join
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!isFolia) return;
        
        Player player = event.getPlayer();
        recentArmorCheckPlayers.put(player.getUniqueId(), System.currentTimeMillis());
        
        // AE applies effects 20 ticks after join (see ArmorWearTrigger.onPlayerJoin)
        // We track for a window to catch these effects
        // Use GlobalRegionScheduler delayed task instead of Thread.sleep to avoid blocking region thread
        scheduleRemove(player.getUniqueId(), TRACK_TIMEOUT);
    }
    
    /**
     * Schedule removal of player from tracking after delay.
     * Uses GlobalRegionScheduler to avoid blocking player's region.
     */
    private void scheduleRemove(UUID uuid, long delayMillis) {
        // Convert millis to ticks (50ms per tick)
        long delayTicks = Math.max(1, delayMillis / 50);
        
        try {
            // Use GlobalRegionScheduler for delayed task - doesn't block any specific region
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
                recentArmorCheckPlayers.remove(uuid);
                processingPlayers.remove(uuid);
            }, delayTicks);
        } catch (Exception e) {
            // Fallback: if GlobalRegionScheduler not available, just remove immediately
            // The cleanup task will handle it eventually
            plugin.getLogger().warning("[AEFoliaFix] Failed to schedule delayed remove, relying on cleanup task");
        }
    }
    
    /**
     * Clean up on quit
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!isFolia) return;
        
        UUID uuid = event.getPlayer().getUniqueId();
        recentArmorCheckPlayers.remove(uuid);
        processingPlayers.remove(uuid);
    }
    
    /**
     * Intercept potion effect events and ensure they're applied on the correct thread.
     * This is the core fix for the Folia async entity modification error.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!isFolia) return;
        
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity livingEntity)) return;
        
        UUID uuid = entity.getUniqueId();
        
        // Skip if we're already processing this player (avoid recursion)
        if (processingPlayers.contains(uuid)) return;
        
        // Only intercept effects for players that were recently checked by AE
        // or if we're definitely on the wrong thread
        boolean shouldIntercept = recentArmorCheckPlayers.containsKey(uuid) || !isOnEntityThread(entity);
        
        if (!shouldIntercept) return;
        
        // Check if we're on the correct thread
        if (isOnEntityThread(entity)) {
            // We're on the correct thread, let it proceed normally
            recentArmorCheckPlayers.remove(uuid);
            return;
        }
        
        // We're on the wrong thread - need to reschedule
        PotionEffect newEffect = event.getNewEffect();
        if (newEffect == null) return;
        
        // Cancel the original event
        event.setCancelled(true);
        
        // Mark as processing to avoid recursion
        processingPlayers.add(uuid);
        
        // Schedule on correct thread using EntityScheduler
        schedulePotionEffect(livingEntity, newEffect, () -> {
            processingPlayers.remove(uuid);
            recentArmorCheckPlayers.remove(uuid);
        });
    }
    
    /**
     * Schedule a potion effect to be applied on the entity's correct thread.
     * Uses EntityScheduler if available, falls back to RegionScheduler.
     */
    private void schedulePotionEffect(LivingEntity entity, PotionEffect effect, Runnable onComplete) {
        try {
            // Try to use EntityScheduler (Folia 1.20.6+)
            Method getSchedulerMethod = entity.getClass().getMethod("getScheduler");
            Object entityScheduler = getSchedulerMethod.invoke(entity);
            
            Method runMethod = entityScheduler.getClass().getMethod("run", 
                org.bukkit.plugin.Plugin.class, 
                java.util.function.Consumer.class, 
                Runnable.class);
            
            runMethod.invoke(entityScheduler, plugin, 
                (java.util.function.Consumer<Object>) task -> {
                    try {
                        entity.addPotionEffect(effect);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[AEFoliaFix] Failed to apply potion effect via EntityScheduler: " + e.getMessage());
                    }
                }, 
                onComplete);
                
        } catch (Exception e) {
            // Fallback to RegionScheduler
            try {
                Bukkit.getRegionScheduler().execute(plugin, entity.getLocation(), () -> {
                    try {
                        entity.addPotionEffect(effect);
                    } catch (Exception ex) {
                        plugin.getLogger().warning("[AEFoliaFix] Failed to apply potion effect via RegionScheduler: " + ex.getMessage());
                    }
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            } catch (Exception ex) {
                plugin.getLogger().warning("[AEFoliaFix] Failed to schedule potion effect, trying direct: " + ex.getMessage());
                // Last resort: try direct application
                entity.addPotionEffect(effect);
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }
    }
    
    /**
     * Check if current thread owns the entity's region.
     * On Folia, entity operations must be performed on the entity's region thread.
     */
    private boolean isOnEntityThread(Entity entity) {
        try {
            Method isOwnedMethod = entity.getClass().getMethod("isOwnedByCurrentRegion");
            return (boolean) isOwnedMethod.invoke(entity);
        } catch (Exception e) {
            // If method doesn't exist, assume we're not on the right thread
            return false;
        }
    }
    
    /**
     * Periodically clean up expired entries from tracking maps
     */
    private void startCleanupTask() {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            long now = System.currentTimeMillis();
            recentArmorCheckPlayers.entrySet().removeIf(entry -> 
                now - entry.getValue() > TRACK_TIMEOUT
            );
        }, 20, 20); // Run every second (20 ticks)
    }
}
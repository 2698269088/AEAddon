package top.mcocet.aEAddon.foliafix;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Universal helper for executing entity operations on the correct Folia scheduler thread.
 * This class handles all entity state modifications that must run on the entity's region thread.
 */
public class FoliaPotionHelper {

    private static final Logger LOGGER = Logger.getLogger("AEAddon-FoliaFix");

    private static boolean isFolia = false;
    private static Object regionScheduler = null;
    private static Method executeMethod = null;
    private static JavaPlugin plugin = null;
    private static boolean initialized = false;

    // Fast MethodHandle cache for isOwnedByCurrentRegion - much faster than reflection
    private static MethodHandle isOwnedMethodHandle = null;
    private static boolean isOwnedMethodChecked = false;

    // Simple caches for non-blocking reads with cleanup support
    private static final Map<UUID, Double> healthCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> maxHealthCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> deadCache = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> absorptionCache = new ConcurrentHashMap<>();

    /**
     * Initialize the helper with the plugin instance.
     */
    public static void init(JavaPlugin pluginInstance) {
        if (initialized) return;
        plugin = pluginInstance;

        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;

            regionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
            executeMethod = regionScheduler.getClass().getMethod("execute",
                    org.bukkit.plugin.Plugin.class, org.bukkit.Location.class, Runnable.class);

            // Pre-initialize MethodHandle for faster thread checks
            initIsOwnedMethodHandle();

            // Start cache cleanup task
            startCacheCleanupTask();

            LOGGER.info("[AEAddon-FoliaFix] FoliaEntityHelper initialized");
        } catch (ClassNotFoundException e) {
            isFolia = false;
            LOGGER.info("[AEAddon-FoliaFix] Not running on Folia, using normal behavior");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[AEAddon-FoliaFix] Failed to initialize FoliaEntityHelper: " + e.getMessage(), e);
        }

        initialized = true;
    }

    /**
     * Initialize MethodHandle for isOwnedByCurrentRegion - much faster than reflection.
     * MethodHandle is invoked directly by the JVM without reflection overhead.
     */
    private static void initIsOwnedMethodHandle() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodType methodType = MethodType.methodType(boolean.class);
            isOwnedMethodHandle = lookup.findVirtual(
                Class.forName("org.bukkit.entity.Entity"), 
                "isOwnedByCurrentRegion", 
                methodType
            );
        } catch (Exception e) {
            // Fallback: MethodHandle not available, will use reflection
            isOwnedMethodHandle = null;
        }
        isOwnedMethodChecked = true;
    }

    /**
     * Start a periodic task to clean up stale cache entries.
     * Prevents memory leaks from offline players.
     */
    private static void startCacheCleanupTask() {
        if (!isFolia || plugin == null) return;
        try {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                cleanupOfflinePlayerCaches();
            }, 6000L, 6000L); // Run every 5 minutes (6000 ticks)
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to start cache cleanup task: " + e.getMessage(), e);
        }
    }

    /**
     * Remove cache entries for players who are no longer online.
     */
    private static void cleanupOfflinePlayerCaches() {
        Set<UUID> onlinePlayers = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player.getUniqueId());
        }
        
        healthCache.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
        maxHealthCache.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
        deadCache.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
        absorptionCache.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
    }

    /**
     * Remove a specific player's cache entries when they quit.
     */
    public static void clearPlayerCache(UUID uuid) {
        healthCache.remove(uuid);
        maxHealthCache.remove(uuid);
        deadCache.remove(uuid);
        absorptionCache.remove(uuid);
    }

    // ==================== Core Execution Methods ====================

    /**
     * Execute a task on the entity's region thread.
     */
    public static void executeOnEntityThread(Entity entity, Runnable task) {
        if (!isFolia || regionScheduler == null || executeMethod == null) {
            task.run();
            return;
        }

        if (isOnEntityThread(entity)) {
            task.run();
            return;
        }

        try {
            executeMethod.invoke(regionScheduler, plugin, entity.getLocation(), task);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to schedule entity task: " + e.getMessage(), e);
            task.run();
        }
    }

    // ==================== Potion Effects ====================

    public static boolean addPotionEffect(LivingEntity entity, PotionEffect effect) {
        if (!isFolia || isOnEntityThread(entity)) {
            return entity.addPotionEffect(effect);
        }
        executeOnEntityThread(entity, () -> entity.addPotionEffect(effect));
        return true;
    }

    public static void removePotionEffect(LivingEntity entity, PotionEffectType type) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.removePotionEffect(type);
            return;
        }
        executeOnEntityThread(entity, () -> entity.removePotionEffect(type));
    }

    // ==================== Movement & Teleport ====================

    public static boolean teleport(Entity entity, Location location) {
        if (!isFolia || isOnEntityThread(entity)) {
            return entity.teleport(location);
        }
        // Use teleportAsync for cross-region teleports on Folia
        try {
            Method teleportAsync = entity.getClass().getMethod("teleportAsync", Location.class);
            teleportAsync.invoke(entity, location);
            return true;
        } catch (Exception e) {
            // Fallback to regular teleport
            executeOnEntityThread(entity, () -> entity.teleport(location));
            return true;
        }
    }

    public static boolean teleport(Entity entity, Location location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        if (!isFolia || isOnEntityThread(entity)) {
            return entity.teleport(location, cause);
        }
        // Use teleportAsync for cross-region teleports on Folia
        try {
            Method teleportAsync = entity.getClass().getMethod("teleportAsync", Location.class, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class);
            teleportAsync.invoke(entity, location, cause);
            return true;
        } catch (Exception e) {
            // Fallback to regular teleport
            executeOnEntityThread(entity, () -> entity.teleport(location, cause));
            return true;
        }
    }

    public static void setVelocity(Entity entity, Vector velocity) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setVelocity(velocity);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setVelocity(velocity));
    }

    public static void setFallDistance(Entity entity, float distance) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setFallDistance(distance);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setFallDistance(distance));
    }

    public static void removeEntity(Entity entity) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.remove();
            return;
        }
        executeOnEntityThread(entity, () -> entity.remove());
    }

    // ==================== Entity Spawning ====================

    /**
     * Spawn an entity at a location on the correct region thread.
     * In Folia, entity spawning must happen on the region thread that owns the location.
     */
    public static <T extends Entity> T spawnEntity(Location location, Class<T> clazz, java.util.function.Consumer<T> configure) {
        if (!isFolia) {
            T entity = location.getWorld().spawn(location, clazz);
            if (configure != null) configure.accept(entity);
            return entity;
        }
        // Check if we're on the correct region thread for this location
        try {
            // Try to spawn directly first - if we're on the right thread it will work
            T entity = location.getWorld().spawn(location, clazz);
            if (configure != null) configure.accept(entity);
            return entity;
        } catch (IllegalStateException e) {
            // Need to spawn on the correct region thread
            // Use global region scheduler as fallback for spawning
            try {
                final Object[] result = new Object[1];
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    try {
                        T entity = location.getWorld().spawn(location, clazz);
                        if (configure != null) configure.accept(entity);
                        result[0] = entity;
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to spawn entity on global thread: " + ex.getMessage());
                    }
                });
                // Return null since spawn is async - caller should handle this
                return null;
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to schedule entity spawn: " + ex.getMessage());
                return null;
            }
        }
    }

    /**
     * Spawn an ExperienceOrb at a location on the correct region thread.
     */
    public static org.bukkit.entity.ExperienceOrb spawnExperienceOrb(Location location, int experience) {
        if (!isFolia) {
            org.bukkit.entity.ExperienceOrb orb = location.getWorld().spawn(location, org.bukkit.entity.ExperienceOrb.class);
            orb.setExperience(experience);
            return orb;
        }
        try {
            org.bukkit.entity.ExperienceOrb orb = location.getWorld().spawn(location, org.bukkit.entity.ExperienceOrb.class);
            orb.setExperience(experience);
            return orb;
        } catch (IllegalStateException e) {
            // Schedule on global region scheduler
            try {
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    try {
                        org.bukkit.entity.ExperienceOrb orb = location.getWorld().spawn(location, org.bukkit.entity.ExperienceOrb.class);
                        orb.setExperience(experience);
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to spawn experience orb: " + ex.getMessage());
                    }
                });
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to schedule experience orb spawn: " + ex.getMessage());
            }
            return null;
        }
    }

    // ==================== Player Movement ====================

    public static void setWalkSpeed(Player player, float speed) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setWalkSpeed(speed);
            return;
        }
        executeOnEntityThread(player, () -> player.setWalkSpeed(speed));
    }

    public static void setFlySpeed(Player player, float speed) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setFlySpeed(speed);
            return;
        }
        executeOnEntityThread(player, () -> player.setFlySpeed(speed));
    }

    public static void setFlying(Player player, boolean flying) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setFlying(flying);
            return;
        }
        executeOnEntityThread(player, () -> player.setFlying(flying));
    }

    public static void setAllowFlight(Player player, boolean allow) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setAllowFlight(allow);
            return;
        }
        executeOnEntityThread(player, () -> player.setAllowFlight(allow));
    }

    // ==================== Health & Damage ====================

    public static void setHealth(LivingEntity entity, double health) {
        if (!isFolia || isOnEntityThread(entity)) {
            setHealthInternal(entity, health);
            return;
        }
        executeOnEntityThread(entity, () -> setHealthInternal(entity, health));
    }

    /**
     * Internal method that handles the actual health setting logic.
     * When health <= 0, uses damage() instead of setHealth() to ensure
     * proper Bukkit event firing (EntityDamageEvent -> EntityDeathEvent)
     * and avoid Folia entity desync issues.
     */
    private static void setHealthInternal(LivingEntity entity, double health) {
        double maxHealth = entity.getMaxHealth();
        double targetHealth = Math.min(health, maxHealth);

        // Critical fix: When setting health to 0 (killing entity),
        // use damage() instead of setHealth() to trigger proper Bukkit events.
        // Direct setHealth(0) bypasses EntityDamageEvent and EntityDeathEvent,
        // causing Folia entity state desync (zombie fake death bug).
        if (targetHealth <= 0.0 && !entity.isDead() && entity.getHealth() > 0.0) {
            // Use maximum damage to ensure death
            // This triggers EntityDamageEvent -> EntityDeathEvent properly
            entity.damage(Integer.MAX_VALUE);
            return;
        }

        entity.setHealth(targetHealth);
    }

    public static void setMaxHealth(LivingEntity entity, double maxHealth) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setMaxHealth(maxHealth);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setMaxHealth(maxHealth));
    }

    public static void damage(LivingEntity entity, double amount) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.damage(amount);
            return;
        }
        executeOnEntityThread(entity, () -> entity.damage(amount));
    }

    public static void damage(LivingEntity entity, double amount, Entity source) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.damage(amount, source);
            return;
        }
        executeOnEntityThread(entity, () -> entity.damage(amount, source));
    }

    public static void setNoDamageTicks(LivingEntity entity, int ticks) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setNoDamageTicks(ticks);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setNoDamageTicks(ticks));
    }

    public static void setFireTicks(LivingEntity entity, int ticks) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setFireTicks(ticks);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setFireTicks(ticks));
    }

    public static void setFreezeTicks(LivingEntity entity, int ticks) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setFreezeTicks(ticks);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setFreezeTicks(ticks));
    }

    // ==================== Entity State ====================

    public static void setAI(LivingEntity entity, boolean ai) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setAI(ai);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setAI(ai));
    }

    public static void setInvulnerable(Entity entity, boolean invulnerable) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setInvulnerable(invulnerable);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setInvulnerable(invulnerable));
    }

    public static void setGlowing(Entity entity, boolean glowing) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setGlowing(glowing);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setGlowing(glowing));
    }

    public static void setInvisible(Entity entity, boolean invisible) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setInvisible(invisible);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setInvisible(invisible));
    }

    public static void setGravity(Entity entity, boolean gravity) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setGravity(gravity);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setGravity(gravity));
    }

    public static void setSilent(Entity entity, boolean silent) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setSilent(silent);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setSilent(silent));
    }

    public static void setVisualFire(Entity entity, boolean fire) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setVisualFire(fire);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setVisualFire(fire));
    }

    // ==================== Player State ====================

    public static void setGameMode(Player player, org.bukkit.GameMode mode) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setGameMode(mode);
            return;
        }
        executeOnEntityThread(player, () -> player.setGameMode(mode));
    }

    public static void setLevel(Player player, int level) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setLevel(level);
            return;
        }
        executeOnEntityThread(player, () -> player.setLevel(level));
    }

    public static void setExp(Player player, float exp) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setExp(exp);
            return;
        }
        executeOnEntityThread(player, () -> player.setExp(exp));
    }

    public static void setTotalExperience(Player player, int exp) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setTotalExperience(exp);
            return;
        }
        executeOnEntityThread(player, () -> player.setTotalExperience(exp));
    }

    public static void setFoodLevel(Player player, int level) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setFoodLevel(level);
            return;
        }
        executeOnEntityThread(player, () -> player.setFoodLevel(level));
    }

    public static void setSaturation(Player player, float saturation) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setSaturation(saturation);
            return;
        }
        executeOnEntityThread(player, () -> player.setSaturation(saturation));
    }

    public static void setExhaustion(Player player, float exhaustion) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setExhaustion(exhaustion);
            return;
        }
        executeOnEntityThread(player, () -> player.setExhaustion(exhaustion));
    }

    public static void setAbsorptionAmount(Player player, double amount) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setAbsorptionAmount(amount);
            return;
        }
        executeOnEntityThread(player, () -> player.setAbsorptionAmount(amount));
    }

    public static void setHealthScale(Player player, double scale) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setHealthScale(scale);
            return;
        }
        executeOnEntityThread(player, () -> player.setHealthScale(scale));
    }

    public static void setHealthScaled(Player player, boolean scaled) {
        if (!isFolia || isOnEntityThread(player)) {
            player.setHealthScaled(scaled);
            return;
        }
        executeOnEntityThread(player, () -> player.setHealthScaled(scaled));
    }

    // ==================== Tameable ====================

    public static void setTamed(org.bukkit.entity.Tameable entity, boolean tamed) {
        if (!isFolia || isOnEntityThread((Entity) entity)) {
            entity.setTamed(tamed);
            return;
        }
        executeOnEntityThread((Entity) entity, () -> entity.setTamed(tamed));
    }

    public static void setOwner(org.bukkit.entity.Tameable entity, org.bukkit.entity.AnimalTamer owner) {
        if (!isFolia || isOnEntityThread((Entity) entity)) {
            entity.setOwner(owner);
            return;
        }
        executeOnEntityThread((Entity) entity, () -> entity.setOwner(owner));
    }

    public static void setSitting(org.bukkit.entity.Sittable entity, boolean sitting) {
        if (!isFolia || isOnEntityThread((Entity) entity)) {
            entity.setSitting(sitting);
            return;
        }
        executeOnEntityThread((Entity) entity, () -> entity.setSitting(sitting));
    }

    public static void setAngry(org.bukkit.entity.Wolf entity, boolean angry) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setAngry(angry);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setAngry(angry));
    }

    // ==================== Ageable ====================

    public static void setBaby(org.bukkit.entity.Ageable entity, boolean baby) {
        if (!isFolia || isOnEntityThread(entity)) {
            if (baby) entity.setBaby(); else entity.setAdult();
            return;
        }
        executeOnEntityThread(entity, () -> { if (baby) entity.setBaby(); else entity.setAdult(); });
    }

    public static void setAge(org.bukkit.entity.Ageable entity, int age) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setAge(age);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setAge(age));
    }

    public static void setBreed(org.bukkit.entity.Ageable entity, boolean breed) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setBreed(breed);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setBreed(breed));
    }

    public static void setLoveModeTicks(org.bukkit.entity.Animals entity, int ticks) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setLoveModeTicks(ticks);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setLoveModeTicks(ticks));
    }

    // ==================== Slime ====================

    public static void setSize(org.bukkit.entity.Slime entity, int size) {
        if (!isFolia || isOnEntityThread(entity)) {
            entity.setSize(size);
            return;
        }
        executeOnEntityThread(entity, () -> entity.setSize(size));
    }

    // ==================== Metadata ====================

    public static void setMetadata(org.bukkit.metadata.Metadatable metadatable, String key, org.bukkit.metadata.MetadataValue value) {
        if (!isFolia) {
            metadatable.setMetadata(key, value);
            return;
        }
        // For entities, execute on entity thread; otherwise execute directly
        if (metadatable instanceof Entity) {
            Entity entity = (Entity) metadatable;
            if (isOnEntityThread(entity)) {
                entity.setMetadata(key, value);
                return;
            }
            executeOnEntityThread(entity, () -> entity.setMetadata(key, value));
            return;
        }
        // For non-entities (blocks, worlds), execute directly
        metadatable.setMetadata(key, value);
    }

    public static void removeMetadata(org.bukkit.metadata.Metadatable metadatable, String key, org.bukkit.plugin.Plugin plugin) {
        if (!isFolia) {
            metadatable.removeMetadata(key, plugin);
            return;
        }
        // For entities, execute on entity thread; otherwise execute directly
        if (metadatable instanceof Entity) {
            Entity entity = (Entity) metadatable;
            if (isOnEntityThread(entity)) {
                entity.removeMetadata(key, plugin);
                return;
            }
            executeOnEntityThread(entity, () -> entity.removeMetadata(key, plugin));
            return;
        }
        // For non-entities (blocks, worlds), execute directly
        metadatable.removeMetadata(key, plugin);
    }

    // ==================== Health Getters (non-blocking with cache) ====================

    /**
     * Get health without blocking. If not on entity thread, returns cached value
     * or schedules async update.
     */
    public static double getHealth(org.bukkit.entity.Damageable entity) {
        if (!isFolia || isOnEntityThread((Entity) entity)) {
            double health = entity.getHealth();
            healthCache.put(entity.getUniqueId(), health);
            return health;
        }
        // Return cached value and schedule update
        Double cached = healthCache.get(entity.getUniqueId());
        if (cached != null) {
            // Schedule cache update without blocking
            executeOnEntityThread((Entity) entity, () -> healthCache.put(entity.getUniqueId(), entity.getHealth()));
            return cached;
        }
        // No cache available - must get value on correct thread
        executeOnEntityThread((Entity) entity, () -> healthCache.put(entity.getUniqueId(), entity.getHealth()));
        return 20.0; // Safe fallback
    }

    /**
     * Get max health without blocking.
     */
    public static double getMaxHealth(org.bukkit.entity.Damageable entity) {
        if (!isFolia || isOnEntityThread((Entity) entity)) {
            double maxHealth = entity.getMaxHealth();
            maxHealthCache.put(entity.getUniqueId(), maxHealth);
            return maxHealth;
        }
        Double cached = maxHealthCache.get(entity.getUniqueId());
        if (cached != null) {
            executeOnEntityThread((Entity) entity, () -> maxHealthCache.put(entity.getUniqueId(), entity.getMaxHealth()));
            return cached;
        }
        executeOnEntityThread((Entity) entity, () -> maxHealthCache.put(entity.getUniqueId(), entity.getMaxHealth()));
        return 20.0;
    }

    /**
     * Check if dead without blocking.
     */
    public static boolean isDead(org.bukkit.entity.Damageable entity) {
        if (!isFolia || isOnEntityThread((Entity) entity)) {
            boolean dead = entity.isDead();
            deadCache.put(entity.getUniqueId(), dead);
            return dead;
        }
        Boolean cached = deadCache.get(entity.getUniqueId());
        if (cached != null) {
            executeOnEntityThread((Entity) entity, () -> deadCache.put(entity.getUniqueId(), entity.isDead()));
            return cached;
        }
        executeOnEntityThread((Entity) entity, () -> deadCache.put(entity.getUniqueId(), entity.isDead()));
        return false;
    }

    /**
     * Get absorption amount without blocking.
     */
    public static double getAbsorptionAmount(Player player) {
        if (!isFolia || isOnEntityThread(player)) {
            double absorption = player.getAbsorptionAmount();
            absorptionCache.put(player.getUniqueId(), absorption);
            return absorption;
        }
        Double cached = absorptionCache.get(player.getUniqueId());
        if (cached != null) {
            executeOnEntityThread(player, () -> absorptionCache.put(player.getUniqueId(), player.getAbsorptionAmount()));
            return cached;
        }
        // No cache available - schedule update and return safe default
        executeOnEntityThread(player, () -> absorptionCache.put(player.getUniqueId(), player.getAbsorptionAmount()));
        return 0.0; // Safe fallback: assume no absorption
    }

    // ==================== Check Thread ====================

    /**
     * Check if current thread owns the entity's region.
     * Uses MethodHandle for optimal performance (much faster than reflection).
     */
    private static boolean isOnEntityThread(Entity entity) {
        if (!isOwnedMethodChecked) {
            initIsOwnedMethodHandle();
        }
        if (isOwnedMethodHandle == null) {
            return false;
        }
        try {
            return (boolean) isOwnedMethodHandle.invoke(entity);
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isFolia() {
        return isFolia;
    }

    // ==================== Command Dispatch (Folia-safe) ====================

    /**
     * Dispatch a command as the console on the global region thread.
     * In Folia, commands must be dispatched on the global region thread.
     */
    public static boolean dispatchConsoleCommand(String command) {
        if (!isFolia) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        // On Folia, dispatch on global region thread
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to dispatch console command: " + command, e);
                }
            });
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to schedule console command: " + command, e);
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }

    /**
     * Dispatch a command as a player on the player's region thread.
     * In Folia, commands must be dispatched on the correct region thread.
     */
    public static boolean dispatchPlayerCommand(LivingEntity entity, String command) {
        if (!isFolia || isOnEntityThread(entity)) {
            return Bukkit.dispatchCommand(entity, command);
        }
        // Schedule on entity's region thread
        executeOnEntityThread(entity, () -> {
            try {
                Bukkit.dispatchCommand(entity, command);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to dispatch player command: " + command, e);
            }
        });
        return true;
    }

    /**
     * Unified command dispatch that handles both console and player commands.
     * Detects if sender is console or entity and dispatches accordingly.
     */
    public static boolean dispatchCommand(org.bukkit.command.CommandSender sender, String command) {
        if (!isFolia) {
            return Bukkit.dispatchCommand(sender, command);
        }
        // On Folia, commands must be dispatched on the correct thread
        if (sender instanceof LivingEntity) {
            return dispatchPlayerCommand((LivingEntity) sender, command);
        }
        // Console or other sender - dispatch on global region thread
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    Bukkit.dispatchCommand(sender, command);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to dispatch command: " + command, e);
                }
            });
            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to schedule command: " + command, e);
            return Bukkit.dispatchCommand(sender, command);
        }
    }
}

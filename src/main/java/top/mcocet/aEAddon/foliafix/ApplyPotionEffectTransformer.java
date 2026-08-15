package top.mcocet.aEAddon.foliafix;

import org.objectweb.asm.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.logging.Level;

/**
 * ASM ClassFileTransformer that modifies AdvancedEnchantments classes to be Folia-compatible.
 *
 * Two types of patches are applied:
 * 1. Entity operation calls (addPotionEffect, setHealth, teleport, etc.) are redirected
 *    through FoliaPotionHelper so they execute on the entity's region thread.
 * 2. Bukkit.getScheduler() calls are redirected through AEFoliaSchedulerHelper so AE
 *    can use Folia's GlobalRegionScheduler / AsyncScheduler without touching the
 *    server's global BukkitScheduler (which would affect other plugins).
 *
 * Only classes inside the AE jar are transformed.
 */
public class ApplyPotionEffectTransformer implements ClassFileTransformer {

    private static java.util.logging.Logger pluginLogger;

    // Debug counters
    private static int transformCallCount = 0;
    private static int schedulerPatchCount = 0;
    private static int bukkitRunnablePatchCount = 0;
    private static int foliaSchedulerAsyncRewriteCount = 0;

    private static final String HELPER_CLASS = "top/mcocet/aEAddon/foliafix/FoliaPotionHelper";
    private static final String SCHEDULER_HELPER_CLASS = "top/mcocet/aEAddon/foliafix/AEFoliaSchedulerHelper";
    private static final String BUKKIT_CLASS = "org/bukkit/Bukkit";
    private static final String BUKKIT_SCHEDULER_CLASS = "org/bukkit/scheduler/BukkitScheduler";

    public static void setPluginLogger(java.util.logging.Logger logger) {
        pluginLogger = logger;
    }

    public static String getDebugStats() {
        return "transformCalls=" + transformCallCount
                + ", schedulerPatches=" + schedulerPatchCount
                + ", bukkitRunnablePatches=" + bukkitRunnablePatchCount
                + ", foliaSchedulerAsyncRewrites=" + foliaSchedulerAsyncRewriteCount;
    }

    private static void logInfo(String message) {
        // Debug logging disabled to reduce console spam.
        // Enable by changing to pluginLogger.info(message) if needed.
    }

    private static void logWarning(String message, Throwable t) {
        if (pluginLogger != null) {
            pluginLogger.log(Level.WARNING, message, t);
        }
        try {
            org.bukkit.Bukkit.getLogger().log(Level.WARNING, message, t);
        } catch (Throwable ignored) {
            // Bukkit may not be available during agent premain.
        }
    }

    // Entity operation methods that must run on the entity's region thread.
    private static final Set<String> ENTITY_TARGET_CLASSES = new HashSet<>(Arrays.asList(
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ApplyPotionEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AddWalkSpeedEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/WalkSpeedEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/FlySpeedEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/FlyEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/TeleportEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/TeleportBehindEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/BoostEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AddHealthEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/RemoveHealthEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DoHarmEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/BurnEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/FreezeEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ExtinguishEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/CureEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/CurePermanentEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PotionOverrideEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/InvincibleEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/GuardEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/StealHealthEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/RemoveHealthDamageEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/RemoveHealthDamageTotemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/RemoveHealthTotemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/KillEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ReviveEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SetAirEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AirEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SetVariableEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/InvertVariableEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ResetComboEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DisableActivationEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DisableKnockbackEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/StopKnockbackEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/IgnoreArmorDamageEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/IgnoreArmorProtectionEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/NegateDamageEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DecreaseDamageEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/IncreaseDamageEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/HalfDamageEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DoubleDamageEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AddDurabilityArmorEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AddDurabilityItemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AddDurabilityCurrentItemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AddFoodEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AddMoneyEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/RemoveMoneyEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/StealMoneyEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/StealExpEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ExpEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ConsoleCommandEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PlayerCommandEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/BroadcastEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/BroadcastPermissionEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/MessageEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ActionbarEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/TitleEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SubtitleEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ParticleEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ParticleLineEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PlaySoundEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PlaySoundOutloudEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PlayEntityEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/FireworkEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/LightningEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ExplodeEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/TntEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/FireballEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ProjectileEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SpawnArrowsEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SpawnEntityEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SpawnBlocksEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SetBlockEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/BreakBlockEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/BreakTreeEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SmeltEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PlantSeedsEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/LavaWalkerEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/WaterWalkerEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/WebWalkerEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PumpkinEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SnowblindEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ScreenFreezeEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/BleedEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/BloodEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/CactusEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DisarmEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DropHeldItemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DropItemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/GiveItemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DeleteItemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/RemoveArmorEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/RemoveRandomArmorEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/RepairEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/ShuffleHotbarEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/TpDropsEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/MoreDropsEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/DropHeadEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/AutoReelEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SetMaxCatchTimeEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/SetMinCatchTimeEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/OpenCraftingTableEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/OpenEnderChestEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PermissionEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/CancelEventEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/CancelUseEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/KeepOnDeathEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/TotemEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PullAwayEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/PullCloserEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/TakeAwayEffect",
            "net/advancedplugins/ae/impl/effects/effects/effects/internal/StealGuardEffect",
            "net/advancedplugins/ae/impl/effects/effects/actions/handlers/DamageHandler"
    ));

    // AE classes that call Bukkit.getScheduler() directly and need to be redirected.
    private static final Set<String> SCHEDULER_TARGET_CLASSES = new HashSet<>(Arrays.asList(
            "net/advancedplugins/ae/Core",
            "net/advancedplugins/ae/globallisteners/listeners/AdminChatListener",
            "net/advancedplugins/ae/globallisteners/listeners/ReloadEvent",
            "net/advancedplugins/ae/globallisteners/listeners/LeaveEvent",
            "net/advancedplugins/ae/handlers/netsharing/MarketInventory",
            "net/advancedplugins/ae/handlers/commands/MainCommand",
            "net/advancedplugins/ae/features/tinkerer/TinkererInventory",
            "net/advancedplugins/ae/features/alchemist/AlchemistInventoryClicks",
            "net/advancedplugins/ae/impl/utils/FoliaScheduler",
            "net/advancedplugins/ae/impl/utils/RunnableMetrics",
            "net/advancedplugins/ae/impl/utils/ReallyFastBlockHandler",
            "net/advancedplugins/ae/impl/utils/TotemUndying",
            "net/advancedplugins/ae/impl/utils/HooksHandler",
            "net/advancedplugins/ae/impl/utils/UpdateChecker",
            "net/advancedplugins/ae/impl/utils/FirstInstall"
    ));

    private static final Set<String> ALL_TARGET_CLASSES = new HashSet<>();

    static {
        ALL_TARGET_CLASSES.addAll(ENTITY_TARGET_CLASSES);
        ALL_TARGET_CLASSES.addAll(SCHEDULER_TARGET_CLASSES);
    }

    public static String[] getTargetClasses() {
        return ALL_TARGET_CLASSES.stream()
                .map(name -> name.replace('/', '.'))
                .toArray(String[]::new);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {

        if (className == null) {
            return null;
        }

        // OPTIMIZATION: Skip Lambda classes immediately - they cannot be retransformed
        if (className.contains("$$Lambda")) {
            return null;
        }

        // Patch all AdvancedEnchantments classes for scheduler issues.
        // Entity operation patches are limited to known effect classes.
        boolean isAeClass = className.startsWith("net/advancedplugins/ae/");
        if (!isAeClass && !ALL_TARGET_CLASSES.contains(className)) {
            return null;
        }

        boolean patchEntity = ENTITY_TARGET_CLASSES.contains(className);
        boolean patchScheduler = isAeClass;
        boolean patchFoliaSchedulerAsync = "net/advancedplugins/ae/impl/utils/FoliaScheduler".equals(className);

        // OPTIMIZATION: If nothing to patch, skip quickly
        if (!patchEntity && !patchScheduler && !patchFoliaSchedulerAsync) {
            return null;
        }

        transformCallCount++;
        logInfo("[AEAddon-FoliaFix] Transforming class: " + className
                + " (entity=" + patchEntity + ", scheduler=" + patchScheduler + ", foliaAsync=" + patchFoliaSchedulerAsync + ")");

        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new SafeClassWriter(reader, loader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new EffectClassVisitor(writer, className, patchEntity, patchScheduler, patchFoliaSchedulerAsync);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            logInfo("[AEAddon-FoliaFix] Transformed class: " + className);
            return writer.toByteArray();
        } catch (Exception e) {
            logWarning("[AEAddon-FoliaFix] Failed to transform class " + className + ": " + e.getMessage(), e);
            return null;
        }
    }

    private static class SafeClassWriter extends ClassWriter {
        private final ClassLoader classLoader;

        public SafeClassWriter(ClassReader reader, ClassLoader classLoader, int flags) {
            super(reader, flags);
            this.classLoader = classLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (TypeNotPresentException e) {
                return "java/lang/Object";
            }
        }
    }

    private static class EffectClassVisitor extends ClassVisitor {
        private final String className;
        private final boolean patchEntity;
        private final boolean patchScheduler;
        private final boolean patchFoliaSchedulerAsync;

        public EffectClassVisitor(ClassVisitor cv, String className, boolean patchEntity,
                                  boolean patchScheduler, boolean patchFoliaSchedulerAsync) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.patchEntity = patchEntity;
            this.patchScheduler = patchScheduler;
            this.patchFoliaSchedulerAsync = patchFoliaSchedulerAsync;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

            if (patchFoliaSchedulerAsync && isFoliaSchedulerAsyncMethod(name, descriptor)) {
                return new FoliaSchedulerAsyncMethodRewriter(mv, name, descriptor);
            }

            return new EffectMethodVisitor(mv, patchEntity, patchScheduler, className.startsWith("net/advancedplugins/ae/"));
        }

        private boolean isFoliaSchedulerAsyncMethod(String name, String descriptor) {
            if ("runTaskAsynchronously".equals(name)) {
                return "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lnet/advancedplugins/ae/impl/utils/FoliaScheduler$Task;".equals(descriptor);
            }
            if ("runTaskLaterAsynchronously".equals(name)) {
                return "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)Lnet/advancedplugins/ae/impl/utils/FoliaScheduler$Task;".equals(descriptor);
            }
            if ("runTaskTimerAsynchronously".equals(name)) {
                return "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)Lnet/advancedplugins/ae/impl/utils/FoliaScheduler$Task;".equals(descriptor);
            }
            return false;
        }
    }

    private static class EffectMethodVisitor extends MethodVisitor {
        private final boolean patchEntity;
        private final boolean patchScheduler;
        private final boolean isAeClass;

        public EffectMethodVisitor(MethodVisitor mv, boolean patchEntity, boolean patchScheduler, boolean isAeClass) {
            super(Opcodes.ASM9, mv);
            this.patchEntity = patchEntity;
            this.patchScheduler = patchScheduler;
            this.isAeClass = isAeClass;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // Replace Bukkit.getScheduler() with AEFoliaSchedulerHelper.getScheduler().
            // The returned proxy handles all subsequent BukkitScheduler method calls.
            if (patchScheduler
                    && opcode == Opcodes.INVOKESTATIC
                    && owner.equals(BUKKIT_CLASS)
                    && name.equals("getScheduler")
                    && descriptor.equals("()L" + BUKKIT_SCHEDULER_CLASS + ";")) {
                schedulerPatchCount++;
                logInfo("[AEAddon-FoliaFix] Patching Bukkit.getScheduler() in class");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, SCHEDULER_HELPER_CLASS, "getScheduler",
                        "()L" + BUKKIT_SCHEDULER_CLASS + ";", false);
                return;
            }

            // Patch BukkitRunnable.runXxx(plugin, ...) calls.
            if (patchScheduler) {
                if (tryPatchBukkitRunnableCall(opcode, owner, name, descriptor)) {
                    return;
                }
                if (tryPatchBukkitRunnableCancel(opcode, owner, name, descriptor)) {
                    return;
                }
            }

            if (patchEntity) {
                if (tryPatchEntityCall(opcode, owner, name, descriptor)) {
                    return;
                }
                if (tryPatchCommandCall(opcode, owner, name, descriptor)) {
                    return;
                }
            }
            // 对所有AE类都拦截teleport调用，不仅限于ENTITY_TARGET_CLASSES
            if (isAeClass && tryPatchTeleportCall(opcode, owner, name, descriptor)) {
                return;
            }

            mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        private boolean tryPatchBukkitRunnableCall(int opcode, String owner, String name, String descriptor) {
            if (opcode != Opcodes.INVOKEVIRTUAL) {
                return false;
            }

            if (!isBukkitRunnableSchedulerName(name)) {
                return false;
            }

            logInfo("[AEAddon-FoliaFix] Detected BukkitRunnable-like call: " + owner + "." + name + " " + descriptor);

            String expectedDescriptor;
            String helperDescriptor;
            switch (name) {
                case "runTask":
                    expectedDescriptor = "(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;";
                    helperDescriptor = "(Lorg/bukkit/scheduler/BukkitRunnable;Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;";
                    break;
                case "runTaskLater":
                    expectedDescriptor = "(Lorg/bukkit/plugin/Plugin;J)Lorg/bukkit/scheduler/BukkitTask;";
                    helperDescriptor = "(Lorg/bukkit/scheduler/BukkitRunnable;Lorg/bukkit/plugin/Plugin;J)Lorg/bukkit/scheduler/BukkitTask;";
                    break;
                case "runTaskTimer":
                    expectedDescriptor = "(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;";
                    helperDescriptor = "(Lorg/bukkit/scheduler/BukkitRunnable;Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;";
                    break;
                case "runTaskAsynchronously":
                    expectedDescriptor = "(Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;";
                    helperDescriptor = "(Lorg/bukkit/scheduler/BukkitRunnable;Lorg/bukkit/plugin/Plugin;)Lorg/bukkit/scheduler/BukkitTask;";
                    break;
                case "runTaskLaterAsynchronously":
                    expectedDescriptor = "(Lorg/bukkit/plugin/Plugin;J)Lorg/bukkit/scheduler/BukkitTask;";
                    helperDescriptor = "(Lorg/bukkit/scheduler/BukkitRunnable;Lorg/bukkit/plugin/Plugin;J)Lorg/bukkit/scheduler/BukkitTask;";
                    break;
                case "runTaskTimerAsynchronously":
                    expectedDescriptor = "(Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;";
                    helperDescriptor = "(Lorg/bukkit/scheduler/BukkitRunnable;Lorg/bukkit/plugin/Plugin;JJ)Lorg/bukkit/scheduler/BukkitTask;";
                    break;
                default:
                    return false;
            }

            if (!descriptor.equals(expectedDescriptor)) {
                logInfo("[AEAddon-FoliaFix] BukkitRunnable." + name + "() descriptor mismatch: expected " + expectedDescriptor + ", got " + descriptor);
                return false;
            }

            bukkitRunnablePatchCount++;
            logInfo("[AEAddon-FoliaFix] Patching BukkitRunnable." + name + "() call (owner=" + owner + ")");
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SCHEDULER_HELPER_CLASS, name, helperDescriptor, false);
            return true;
        }

        private boolean tryPatchBukkitRunnableCancel(int opcode, String owner, String name, String descriptor) {
            // BukkitRunnable.cancel() -> 替换为安全取消
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && name.equals("cancel")
                    && descriptor.equals("()V")) {
                // 检查owner是否是BukkitRunnable或其子类
                if (owner.equals("org/bukkit/scheduler/BukkitRunnable") || isBukkitRunnableSubclass(owner)) {
                    // 替换为调用 AEFoliaSchedulerHelper.cancelBukkitRunnable(this)
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, SCHEDULER_HELPER_CLASS, "cancelBukkitRunnable",
                            "(Lorg/bukkit/scheduler/BukkitRunnable;)V", false);
                    return true;
                }
            }
            return false;
        }

        private boolean isBukkitRunnableSubclass(String owner) {
            // 简化检查：如果是AE包内的类，假设可能继承BukkitRunnable
            return owner.startsWith("net/advancedplugins/ae/");
        }

        private static boolean isBukkitRunnableSchedulerName(String name) {
            return "runTask".equals(name)
                    || "runTaskLater".equals(name)
                    || "runTaskTimer".equals(name)
                    || "runTaskAsynchronously".equals(name)
                    || "runTaskLaterAsynchronously".equals(name)
                    || "runTaskTimerAsynchronously".equals(name);
        }

        private boolean tryPatchEntityCall(int opcode, String owner, String name, String descriptor) {
            // LivingEntity.addPotionEffect(PotionEffect)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/LivingEntity")
                    && name.equals("addPotionEffect")
                    && descriptor.equals("(Lorg/bukkit/potion/PotionEffect;)Z")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "addPotionEffect",
                        "(Lorg/bukkit/entity/LivingEntity;Lorg/bukkit/potion/PotionEffect;)Z", false);
                return true;
            }

            // LivingEntity.removePotionEffect(PotionEffectType)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/LivingEntity")
                    && name.equals("removePotionEffect")
                    && descriptor.equals("(Lorg/bukkit/potion/PotionEffectType;)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "removePotionEffect",
                        "(Lorg/bukkit/entity/LivingEntity;Lorg/bukkit/potion/PotionEffectType;)V", false);
                return true;
            }

            // Player.setWalkSpeed(float)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Player")
                    && name.equals("setWalkSpeed")
                    && descriptor.equals("(F)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setWalkSpeed",
                        "(Lorg/bukkit/entity/Player;F)V", false);
                return true;
            }

            // Player.setFlySpeed(float)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Player")
                    && name.equals("setFlySpeed")
                    && descriptor.equals("(F)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setFlySpeed",
                        "(Lorg/bukkit/entity/Player;F)V", false);
                return true;
            }

            // Player.setFlying(boolean)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Player")
                    && name.equals("setFlying")
                    && descriptor.equals("(Z)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setFlying",
                        "(Lorg/bukkit/entity/Player;Z)V", false);
                return true;
            }

            // Player.setAllowFlight(boolean)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Player")
                    && name.equals("setAllowFlight")
                    && descriptor.equals("(Z)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setAllowFlight",
                        "(Lorg/bukkit/entity/Player;Z)V", false);
                return true;
            }

            // Entity.teleport(Location)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && (owner.equals("org/bukkit/entity/Entity") || owner.equals("org/bukkit/entity/LivingEntity"))
                    && name.equals("teleport")
                    && descriptor.equals("(Lorg/bukkit/Location;)Z")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "teleport",
                        "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)Z", false);
                return true;
            }

            // Entity.teleport(Location, TeleportCause)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && (owner.equals("org/bukkit/entity/Entity") || owner.equals("org/bukkit/entity/LivingEntity"))
                    && name.equals("teleport")
                    && descriptor.equals("(Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "teleport",
                        "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z", false);
                return true;
            }

            // Entity.setVelocity(Vector)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Entity")
                    && name.equals("setVelocity")
                    && descriptor.equals("(Lorg/bukkit/util/Vector;)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setVelocity",
                        "(Lorg/bukkit/entity/Entity;Lorg/bukkit/util/Vector;)V", false);
                return true;
            }

            // Entity.remove()
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Entity")
                    && name.equals("remove")
                    && descriptor.equals("()V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "removeEntity",
                        "(Lorg/bukkit/entity/Entity;)V", false);
                return true;
            }

            // LivingEntity.setHealth(double) / Damageable.setHealth(double)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && (owner.equals("org/bukkit/entity/LivingEntity") || owner.equals("org/bukkit/entity/Damageable"))
                    && name.equals("setHealth")
                    && descriptor.equals("(D)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setHealth",
                        "(Lorg/bukkit/entity/LivingEntity;D)V", false);
                return true;
            }

            // LivingEntity.damage(double)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/LivingEntity")
                    && name.equals("damage")
                    && descriptor.equals("(D)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "damage",
                        "(Lorg/bukkit/entity/LivingEntity;D)V", false);
                return true;
            }

            // Damageable.damage(double, Entity)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Damageable")
                    && name.equals("damage")
                    && descriptor.equals("(DLorg/bukkit/entity/Entity;)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "damage",
                        "(Lorg/bukkit/entity/LivingEntity;DLorg/bukkit/entity/Entity;)V", false);
                return true;
            }

            // LivingEntity.setFireTicks(int)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/LivingEntity")
                    && name.equals("setFireTicks")
                    && descriptor.equals("(I)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setFireTicks",
                        "(Lorg/bukkit/entity/LivingEntity;I)V", false);
                return true;
            }

            // LivingEntity.setAI(boolean)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/LivingEntity")
                    && name.equals("setAI")
                    && descriptor.equals("(Z)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setAI",
                        "(Lorg/bukkit/entity/LivingEntity;Z)V", false);
                return true;
            }

            // Entity.setInvulnerable(boolean)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Entity")
                    && name.equals("setInvulnerable")
                    && descriptor.equals("(Z)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setInvulnerable",
                        "(Lorg/bukkit/entity/Entity;Z)V", false);
                return true;
            }

            // LivingEntity.getHealth() / Damageable.getHealth()
            if (opcode == Opcodes.INVOKEINTERFACE
                    && (owner.equals("org/bukkit/entity/LivingEntity") || owner.equals("org/bukkit/entity/Damageable"))
                    && name.equals("getHealth")
                    && descriptor.equals("()D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "getHealth",
                        "(Lorg/bukkit/entity/Damageable;)D", false);
                return true;
            }

            // LivingEntity.getMaxHealth() / Damageable.getMaxHealth()
            if (opcode == Opcodes.INVOKEINTERFACE
                    && (owner.equals("org/bukkit/entity/LivingEntity") || owner.equals("org/bukkit/entity/Damageable"))
                    && name.equals("getMaxHealth")
                    && descriptor.equals("()D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "getMaxHealth",
                        "(Lorg/bukkit/entity/Damageable;)D", false);
                return true;
            }

            // LivingEntity.isDead() / Damageable.isDead()
            if (opcode == Opcodes.INVOKEINTERFACE
                    && (owner.equals("org/bukkit/entity/LivingEntity") || owner.equals("org/bukkit/entity/Damageable"))
                    && name.equals("isDead")
                    && descriptor.equals("()Z")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "isDead",
                        "(Lorg/bukkit/entity/Damageable;)Z", false);
                return true;
            }

            // Player.getAbsorptionAmount()
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Player")
                    && name.equals("getAbsorptionAmount")
                    && descriptor.equals("()D")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "getAbsorptionAmount",
                        "(Lorg/bukkit/entity/Player;)D", false);
                return true;
            }

            // Damageable.setAbsorptionAmount(double)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && owner.equals("org/bukkit/entity/Damageable")
                    && name.equals("setAbsorptionAmount")
                    && descriptor.equals("(D)V")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "setAbsorptionAmount",
                        "(Lorg/bukkit/entity/Player;D)V", false);
                return true;
            }

            return false;
        }

        private boolean tryPatchCommandCall(int opcode, String owner, String name, String descriptor) {
            // Bukkit.dispatchCommand(CommandSender, String)
            // ConsoleCommandEffect: Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            // PlayerCommandEffect: Bukkit.dispatchCommand(entity, command)
            if (opcode == Opcodes.INVOKESTATIC
                    && owner.equals("org/bukkit/Bukkit")
                    && name.equals("dispatchCommand")
                    && descriptor.equals("(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z")) {
                // We can't easily distinguish console vs player dispatch at bytecode level
                // So we use a unified method that handles both
                // The CommandSender on stack will be passed to our helper
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "dispatchCommand",
                        "(Lorg/bukkit/command/CommandSender;Ljava/lang/String;)Z", false);
                return true;
            }

            return false;
        }

        private boolean tryPatchTeleportCall(int opcode, String owner, String name, String descriptor) {
            // Entity.teleport(Location)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && (owner.equals("org/bukkit/entity/Entity") || owner.equals("org/bukkit/entity/LivingEntity"))
                    && name.equals("teleport")
                    && descriptor.equals("(Lorg/bukkit/Location;)Z")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "teleport",
                        "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;)Z", false);
                return true;
            }

            // Entity.teleport(Location, TeleportCause)
            if (opcode == Opcodes.INVOKEINTERFACE
                    && (owner.equals("org/bukkit/entity/Entity") || owner.equals("org/bukkit/entity/LivingEntity"))
                    && name.equals("teleport")
                    && descriptor.equals("(Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z")) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_CLASS, "teleport",
                        "(Lorg/bukkit/entity/Entity;Lorg/bukkit/Location;Lorg/bukkit/event/player/PlayerTeleportEvent$TeleportCause;)Z", false);
                return true;
            }

            return false;
        }
    }

    /**
     * Rewrites AE's FoliaScheduler async methods to use AEFoliaSchedulerHelper.
     *
     * AE's original implementation routes async tasks through GlobalRegionScheduler,
     * which is wrong (they should run on the AsyncScheduler). This rewriter replaces
     * the entire method body with a simple delegation to AEFoliaSchedulerHelper.
     */
    private static class FoliaSchedulerAsyncMethodRewriter extends MethodVisitor {

        private static final String TASK_CLASS = "net/advancedplugins/ae/impl/utils/FoliaScheduler$Task";
        private static final String TASK_INIT = "<init>";
        private static final String TASK_INIT_DESC = "(Lorg/bukkit/scheduler/BukkitTask;)V";

        private final String methodName;
        private final String methodDescriptor;
        private boolean codeStarted = false;

        public FoliaSchedulerAsyncMethodRewriter(MethodVisitor mv, String methodName, String methodDescriptor) {
            super(Opcodes.ASM9, mv);
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
        }

        @Override
        public void visitCode() {
            codeStarted = true;
            mv.visitCode();
            foliaSchedulerAsyncRewriteCount++;
            logInfo("[AEAddon-FoliaFix] Rewriting FoliaScheduler async method: " + methodName);
            emitNewBody();
        }

        @Override
        public void visitInsn(int opcode) {
            // Ignore original instructions.
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            // Ignore original instructions.
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            // Ignore original instructions.
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // Ignore original instructions.
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // Ignore original instructions.
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // Ignore original instructions.
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            // Ignore original instructions.
        }

        @Override
        public void visitLabel(Label label) {
            // Ignore original instructions.
        }

        @Override
        public void visitLdcInsn(Object value) {
            // Ignore original instructions.
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            // Ignore original instructions.
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            // Ignore original instructions.
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            // Ignore original instructions.
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            // Ignore original instructions.
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            // Ignore original frames; COMPUTE_FRAMES will recalculate.
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            // Ignore original try/catch blocks.
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            // Ignore original line numbers.
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            // Ignore original local variable table.
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (!codeStarted) {
                // visitCode was never called; emit a minimal body to keep the class valid.
                mv.visitCode();
                emitNewBody();
            }
            mv.visitMaxs(-1, -1);
        }

        @Override
        public void visitEnd() {
            mv.visitEnd();
        }

        private void emitNewBody() {
            // return new Task(AEFoliaSchedulerHelper.runXxxAsynchronously(...));
            mv.visitTypeInsn(Opcodes.NEW, TASK_CLASS);
            mv.visitInsn(Opcodes.DUP);

            mv.visitVarInsn(Opcodes.ALOAD, 0); // plugin
            mv.visitVarInsn(Opcodes.ALOAD, 1); // runnable

            String helperMethod;
            String helperDescriptor;

            if ("runTaskAsynchronously".equals(methodName)) {
                helperMethod = "runTaskAsynchronously";
                helperDescriptor = "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;";
            } else if ("runTaskLaterAsynchronously".equals(methodName)) {
                helperMethod = "runTaskLaterAsynchronously";
                helperDescriptor = "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;J)Lorg/bukkit/scheduler/BukkitTask;";
                mv.visitVarInsn(Opcodes.LLOAD, 2); // delay
            } else if ("runTaskTimerAsynchronously".equals(methodName)) {
                helperMethod = "runTaskTimerAsynchronously";
                helperDescriptor = "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;JJ)Lorg/bukkit/scheduler/BukkitTask;";
                mv.visitVarInsn(Opcodes.LLOAD, 2); // delay
                mv.visitVarInsn(Opcodes.LLOAD, 4); // period
            } else {
                throw new IllegalStateException("Unexpected method: " + methodName);
            }

            mv.visitMethodInsn(Opcodes.INVOKESTATIC, SCHEDULER_HELPER_CLASS, helperMethod, helperDescriptor, false);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TASK_CLASS, TASK_INIT, TASK_INIT_DESC, false);
            mv.visitInsn(Opcodes.ARETURN);
        }
    }
}
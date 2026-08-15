package top.mcocet.aEAddon.foliafix;

import net.advancedplugins.ae.impl.effects.effects.abilities.DisabledAbility;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Thread-safe replacement for ActionExecution's static disabledAbilities accessors.
 *
 * AE stores disabled abilities in a plain static HashMap&lt;UUID, List&lt;DisabledAbility&gt;&gt;.
 * On Folia, the REPEATING trigger runs on the global tick thread and streams that list
 * in getDisabledAbilities(), while region threads (e.g. combat events triggering
 * DisableActivationEffect) mutate it in addDisabledAbility(), causing
 * ConcurrentModificationException. The ActionExecution bytecode is patched to delegate
 * both methods here, where all access to the map is serialized on a single lock.
 */
public final class ActionExecutionConcurrencyHelper {

    private static final Logger LOGGER = Logger.getLogger("AEAddon-FoliaFix");
    private static final Object LOCK = new Object();

    private static volatile Map<UUID, List<DisabledAbility>> disabledAbilitiesMap;

    private ActionExecutionConcurrencyHelper() {
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, List<DisabledAbility>> getMap() {
        Map<UUID, List<DisabledAbility>> map = disabledAbilitiesMap;
        if (map == null) {
            synchronized (LOCK) {
                map = disabledAbilitiesMap;
                if (map == null) {
                    try {
                        Class<?> clazz = Class.forName("net.advancedplugins.ae.impl.effects.effects.actions.ActionExecution");
                        Field field = clazz.getDeclaredField("disabledAbilities");
                        field.setAccessible(true);
                        map = (Map<UUID, List<DisabledAbility>>) field.get(null);
                        disabledAbilitiesMap = map;
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "[AEAddon-FoliaFix] Failed to access ActionExecution.disabledAbilities: " + e.getMessage(), e);
                        return null;
                    }
                }
            }
        }
        return map;
    }

    /**
     * Replaces ActionExecution.addDisabledAbility(UUID, String, int).
     * Logic identical to the original, but serialized on LOCK.
     */
    public static void addDisabledAbility(UUID entity, String ability, int seconds) {
        Map<UUID, List<DisabledAbility>> map = getMap();
        if (map == null) {
            return;
        }
        synchronized (LOCK) {
            map.computeIfAbsent(entity, k -> new ArrayList<>())
                    .add(new DisabledAbility((long) seconds * 1000L, ability));
        }
    }

    /**
     * Replaces ActionExecution.getDisabledAbilities(UUID).
     * Logic identical to the original, but serialized on LOCK.
     */
    public static List<String> getDisabledAbilities(UUID entity) {
        Map<UUID, List<DisabledAbility>> map = getMap();
        if (map == null) {
            return new ArrayList<>();
        }
        synchronized (LOCK) {
            return map.getOrDefault(entity, new ArrayList<>()).stream()
                    .filter(a -> a.getActivatesOn() - System.currentTimeMillis() > 0L)
                    .map(DisabledAbility::getAbility)
                    .collect(Collectors.toList());
        }
    }
}

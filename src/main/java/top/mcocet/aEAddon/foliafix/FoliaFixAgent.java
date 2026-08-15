package top.mcocet.aEAddon.foliafix;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Java Agent for Folia compatibility patching.
 * 
 * This agent is loaded at JVM startup via -javaagent flag and registers
 * the ASM transformer before any plugin classes are loaded.
 * 
 * Usage: Add to server startup flags:
 *   -javaagent:plugins/AEAddon-1.0.jar
 */
public class FoliaFixAgent {

    private static final Logger LOGGER = Logger.getLogger("AEAddon-FoliaFix");

    /**
     * Called when the agent is loaded at JVM startup via -javaagent
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        LOGGER.info("[AEAddon-FoliaFix] Agent loaded via premain");
        init(inst);
    }

    /**
     * Called when the agent is loaded dynamically after JVM startup
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        LOGGER.info("[AEAddon-FoliaFix] Agent loaded via agentmain");
        init(inst);
    }

    private static void init(Instrumentation inst) {
        // Register the ASM transformer
        // This will intercept class loading and transform AE classes before they are loaded
        ApplyPotionEffectTransformer transformer = new ApplyPotionEffectTransformer();
        inst.addTransformer(transformer, true);

        LOGGER.info("[AEAddon-FoliaFix] ASM transformer registered. AE classes will be transformed on load.");
    }
}

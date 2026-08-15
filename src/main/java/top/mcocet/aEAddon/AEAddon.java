package top.mcocet.aEAddon;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.aEAddon.foliafix.FoliaPotionHelper;

public final class AEAddon extends JavaPlugin implements Listener {

    private static AEAddon instance;
    private Scheduler scheduler;
    private EnchantBookGUI enchantBookGUI;
    private boolean aePatchApplied = false;

    @Override
    public void onEnable() {
        instance = this;
        
        // 检测是否运行在Folia上
        boolean isFolia = isFolia();
        if (isFolia) {
            getLogger().info("检测到Folia服务端，使用Folia调度器");
            this.scheduler = new FoliaScheduler(this);
        } else {
            getLogger().info("检测到Bukkit/Paper服务端，使用Bukkit调度器");
            this.scheduler = new BukkitScheduler(this);
        }
        
        // 初始化FoliaPotionHelper（用于Agent转换后的类）
        FoliaPotionHelper.init(this);
        
        // 初始化GUI
        this.enchantBookGUI = new EnchantBookGUI(this);
        
        // 注册命令
        AEAddonCommand commandExecutor = new AEAddonCommand(this);
        getCommand("aeaddon").setExecutor(commandExecutor);
        getCommand("aeaddon").setTabCompleter(commandExecutor);
        
        // 注册事件
        Bukkit.getPluginManager().registerEvents(new GUIListener(this), this);
        
        // 注册Folia兼容性修复监听器
        if (isFolia) {
            try {
                Bukkit.getPluginManager().registerEvents(new top.mcocet.aEAddon.foliafix.AEFoliaFixListener(this), this);
                getLogger().info("[AEAddon] Folia兼容性修复监听器已注册");
            } catch (Exception e) {
                getLogger().warning("[AEAddon] 无法注册Folia兼容性修复监听器: " + e.getMessage());
            }
            
            // 立即应用ByteBuddy字节码补丁（在AE加载前注册转换器）
            // 这样AE类加载时会自动被转换
            try {
                top.mcocet.aEAddon.foliafix.AEFoliaByteBuddyPatcher.init(this);
                getLogger().info("[AEAddon] ByteBuddy补丁已注册，AE类加载时将自动转换");
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.WARNING, "[AEAddon] 无法应用ByteBuddy补丁: " + e.getMessage(), e);
            }
            
            // 监听AE插件启动事件，在AE启动后再次确认补丁
            Bukkit.getPluginManager().registerEvents(this, this);
        }
        
        getLogger().info("AEAddon 已加载!");
        getLogger().info("使用 /aeaddon 或 /aea 打开附魔书管理面板");
    }
    
    /**
     * 监听插件启动事件，当AE启动时应用额外的补丁
     */
    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equals("AdvancedEnchantments") && !aePatchApplied) {
            getLogger().info("[AEAddon] 检测到 AdvancedEnchantments 已启动，应用额外补丁...");
            try {
                // 重新尝试转换已加载的AE类
                top.mcocet.aEAddon.foliafix.AEFoliaByteBuddyPatcher.retransformLoadedClasses(this);
                aePatchApplied = true;
                getLogger().info("[AEAddon] AdvancedEnchantments 额外补丁应用成功");
            } catch (Exception e) {
                getLogger().warning("[AEAddon] 无法应用额外补丁: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("AEAddon 已卸载!");
    }

    public static AEAddon getInstance() {
        return instance;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public EnchantBookGUI getEnchantBookGUI() {
        return enchantBookGUI;
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

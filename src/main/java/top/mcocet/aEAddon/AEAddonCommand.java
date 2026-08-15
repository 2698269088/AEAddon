package top.mcocet.aEAddon;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AEAddonCommand implements CommandExecutor, TabCompleter {
    
    private final AEAddon plugin;
    
    public AEAddonCommand(AEAddon plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c[AEAddon] 此命令只能由玩家执行！"));
            return true;
        }
        
        Player player = (Player) sender;
        
        // 检查权限
        if (!player.hasPermission("aeaddon.admin")) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c[AEAddon] 你没有权限使用此命令！"));
            return true;
        }
        
        // 打开主菜单
        plugin.getScheduler().runTask(() -> plugin.getEnchantBookGUI().openMainMenu(player));
        return true;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<>();
    }
}

package top.mcocet.aEAddon;

import net.advancedplugins.ae.api.AEAPI;
import net.advancedplugins.ae.enchanthandler.enchantments.AdvancedEnchantment;
import net.advancedplugins.ae.enchanthandler.enchantments.AdvancedGroup;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class GUIListener implements Listener {
    
    private final AEAddon plugin;
    
    public GUIListener(AEAddon plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getWhoClicked();
        InventoryHolder holder = event.getInventory().getHolder();
        
        if (!(holder instanceof EnchantBookGUI.AEAddonHolder)) {
            return;
        }
        
        event.setCancelled(true);
        
        EnchantBookGUI.AEAddonHolder aeHolder = (EnchantBookGUI.AEAddonHolder) holder;
        EnchantBookGUI.GUIType type = aeHolder.getType();
        ItemStack clickedItem = event.getCurrentItem();
        
        if (clickedItem == null || clickedItem.getType() == Material.AIR || clickedItem.getType() == Material.BLACK_STAINED_GLASS_PANE) {
            return;
        }
        
        switch (type) {
            case MAIN_MENU:
                handleMainMenuClick(player, clickedItem);
                break;
            case GROUP_MENU:
                handleGroupMenuClick(player, clickedItem, aeHolder);
                break;
            case ENCHANT_MENU:
                handleEnchantMenuClick(player, clickedItem, aeHolder);
                break;
        }
    }
    
    private void handleMainMenuClick(Player player, ItemStack clickedItem) {
        // 优先使用PersistentDataContainer获取品质组名称
        String groupName = plugin.getEnchantBookGUI().getGroupNameFromItem(clickedItem);
        
        if (groupName != null) {
            AdvancedGroup group = AdvancedGroup.matchGroup(groupName);
            if (group != null) {
                plugin.getEnchantBookGUI().openGroupMenu(player, group, 1);
                return;
            }
        }
        
        // 回退：通过显示名称匹配
        String displayName = clickedItem.getItemMeta().getDisplayName();
        String groupDisplayName = ChatColor.stripColor(displayName).replace(" 附魔", "");
        
        // 查找对应的品质组
        for (String gn : AEAPI.getGroups()) {
            AdvancedGroup group = AdvancedGroup.matchGroup(gn);
            if (group != null && group.getDisplayName().equalsIgnoreCase(groupDisplayName)) {
                plugin.getEnchantBookGUI().openGroupMenu(player, group, 1);
                return;
            }
        }
    }
    
    private void handleGroupMenuClick(Player player, ItemStack clickedItem, EnchantBookGUI.AEAddonHolder holder) {
        Material material = clickedItem.getType();
        String displayName = clickedItem.getItemMeta().getDisplayName();
        
        // 返回主菜单
        if (material == Material.ARROW && displayName.contains("返回主菜单")) {
            plugin.getEnchantBookGUI().openMainMenu(player);
            return;
        }
        
        // 上一页
        if (material == Material.PAPER && displayName.contains("上一页")) {
            AdvancedGroup group = AdvancedGroup.matchGroup(holder.getGroupName());
            if (group != null) {
                plugin.getEnchantBookGUI().openGroupMenu(player, group, holder.getPage() - 1);
            }
            return;
        }
        
        // 下一页
        if (material == Material.PAPER && displayName.contains("下一页")) {
            AdvancedGroup group = AdvancedGroup.matchGroup(holder.getGroupName());
            if (group != null) {
                plugin.getEnchantBookGUI().openGroupMenu(player, group, holder.getPage() + 1);
            }
            return;
        }
        
        // 点击附魔物品 - 使用PersistentDataContainer获取附魔path
        if (material == Material.ENCHANTED_BOOK) {
            String enchantPath = plugin.getEnchantBookGUI().getEnchantPathFromItem(clickedItem);
            AdvancedGroup group = AdvancedGroup.matchGroup(holder.getGroupName());
            
            if (enchantPath != null && group != null) {
                AdvancedEnchantment enchant = AEAPI.getEnchantmentInstance(enchantPath);
                if (enchant != null) {
                    plugin.getEnchantBookGUI().openEnchantMenu(player, enchant, group);
                    return;
                }
            }
            
            // 回退：尝试通过显示名称匹配
            String enchantDisplayName = ChatColor.stripColor(displayName);
            if (group != null) {
                for (String enchantName : AEAPI.getEnchantmentsByGroup(group.getName())) {
                    AdvancedEnchantment enchant = AEAPI.getEnchantmentInstance(enchantName);
                    if (enchant != null) {
                        String enchantDisplay = enchant.getDisplayNoColor();
                        if (enchantDisplay.equalsIgnoreCase(enchantDisplayName) || 
                            ChatColor.stripColor(enchant.getDisplay()).equalsIgnoreCase(enchantDisplayName)) {
                            plugin.getEnchantBookGUI().openEnchantMenu(player, enchant, group);
                            return;
                        }
                    }
                }
            }
        }
    }
    
    private void handleEnchantMenuClick(Player player, ItemStack clickedItem, EnchantBookGUI.AEAddonHolder holder) {
        Material material = clickedItem.getType();
        String displayName = clickedItem.getItemMeta().getDisplayName();
        
        // 返回品质菜单
        if (material == Material.ARROW && displayName.contains("返回品质菜单")) {
            AdvancedGroup group = AdvancedGroup.matchGroup(holder.getGroupName());
            if (group != null) {
                plugin.getEnchantBookGUI().openGroupMenu(player, group, 1);
            }
            return;
        }
        
        // 点击附魔书 - 给予玩家
        if (material == Material.ENCHANTED_BOOK) {
            // 直接给予物品
            ItemStack book = clickedItem.clone();
            
            // 移除我们的自定义lore
            if (book.hasItemMeta() && book.getItemMeta().hasLore()) {
                org.bukkit.inventory.meta.ItemMeta meta = book.getItemMeta();
                java.util.List<String> lore = meta.getLore();
                java.util.List<String> newLore = new java.util.ArrayList<>();
                
                for (String line : lore) {
                    if (!line.contains("点击获取此附魔书") && !line.contains("品质:")) {
                        newLore.add(line);
                    }
                }
                
                meta.setLore(newLore);
                book.setItemMeta(meta);
            }
            
            // 给予玩家物品
            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(book);
            if (!leftover.isEmpty()) {
                // 背包满了，掉落在地上
                for (ItemStack item : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a[AEAddon] &e背包已满，附魔书已掉落在地上！"));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a[AEAddon] &7已成功获取附魔书！"));
            }
            
            // 播放音效
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
        }
    }
    
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        
        Player player = (Player) event.getPlayer();
        InventoryHolder holder = event.getInventory().getHolder();
        
        if (holder instanceof EnchantBookGUI.AEAddonHolder) {
            plugin.getEnchantBookGUI().clearPlayerState(player);
        }
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // 清理GUI状态防止内存泄漏
        plugin.getEnchantBookGUI().clearPlayerState(player);
        // 清理Folia缓存防止内存泄漏
        top.mcocet.aEAddon.foliafix.FoliaPotionHelper.clearPlayerCache(player.getUniqueId());
    }
}
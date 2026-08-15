package top.mcocet.aEAddon;

import net.advancedplugins.ae.api.AEAPI;
import net.advancedplugins.ae.enchanthandler.enchantments.AdvancedEnchantment;
import net.advancedplugins.ae.enchanthandler.enchantments.AdvancedGroup;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class EnchantBookGUI {
    
    private final AEAddon plugin;
    private final Map<UUID, GUIState> playerStates = new HashMap<>();
    private final NamespacedKey enchantKey;
    private final NamespacedKey groupKey;
    
    // GUI标题
    public static final String MAIN_MENU_TITLE = ChatColor.translateAlternateColorCodes('&', "&8[&bAEAddon&8] &7附魔书管理");
    public static final String GROUP_MENU_TITLE_PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&bAEAddon&8] &7品质: ");
    public static final String ENCHANT_MENU_TITLE_PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&bAEAddon&8] &7附魔: ");
    
    public EnchantBookGUI(AEAddon plugin) {
        this.plugin = plugin;
        this.enchantKey = new NamespacedKey(plugin, "enchant_path");
        this.groupKey = new NamespacedKey(plugin, "group_name");
    }
    
    /**
     * 打开主菜单 - 显示所有品质组
     */
    public void openMainMenu(Player player) {
        List<AdvancedGroup> groups = getSortedGroups();
        int size = Math.max(9, ((groups.size() - 1) / 9 + 1) * 9);
        size = Math.min(size, 54);
        
        Inventory inventory = Bukkit.createInventory(new AEAddonHolder(GUIType.MAIN_MENU, null, null, 1), size, MAIN_MENU_TITLE);
        
        for (int i = 0; i < groups.size() && i < size; i++) {
            AdvancedGroup group = groups.get(i);
            ItemStack item = createGroupItem(group);
            inventory.setItem(i, item);
        }
        
        // 填充空白
        fillEmptySlots(inventory, createGlassPane());
        
        player.openInventory(inventory);
        playerStates.put(player.getUniqueId(), new GUIState(GUIType.MAIN_MENU, null, null, 1));
    }
    
    /**
     * 打开品质菜单 - 显示该品质下的所有附魔
     */
    public void openGroupMenu(Player player, AdvancedGroup group, int page) {
        List<AdvancedEnchantment> enchants = getEnchantsByGroup(group);
        int itemsPerPage = 45; // 5行，留一行给导航
        int totalPages = (int) Math.ceil((double) enchants.size() / itemsPerPage);
        page = Math.max(1, Math.min(page, totalPages));
        
        String title = GROUP_MENU_TITLE_PREFIX + group.getColor() + group.getDisplayName() + " &7(" + page + "/" + totalPages + ")";
        title = ChatColor.translateAlternateColorCodes('&', title);
        if (title.length() > 32) title = title.substring(0, 32);
        
        Inventory inventory = Bukkit.createInventory(new AEAddonHolder(GUIType.GROUP_MENU, group.getName(), null, page), 54, title);
        
        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, enchants.size());
        
        for (int i = startIndex; i < endIndex; i++) {
            AdvancedEnchantment enchant = enchants.get(i);
            ItemStack item = createEnchantItem(enchant, group);
            inventory.setItem(i - startIndex, item);
        }
        
        // 导航栏 (最后一行)
        int navRow = 45;
        
        // 返回按钮
        inventory.setItem(navRow + 3, createNavigationItem(Material.ARROW, "&c返回主菜单"));
        
        // 上一页
        if (page > 1) {
            inventory.setItem(navRow + 1, createNavigationItem(Material.PAPER, "&e上一页"));
        }
        
        // 下一页
        if (page < totalPages) {
            inventory.setItem(navRow + 5, createNavigationItem(Material.PAPER, "&e下一页"));
        }
        
        // 页码信息
        inventory.setItem(navRow + 4, createInfoItem("&7页码: &f" + page + "/" + totalPages));
        
        // 填充空白
        fillEmptySlots(inventory, createGlassPane());
        
        player.openInventory(inventory);
        playerStates.put(player.getUniqueId(), new GUIState(GUIType.GROUP_MENU, group.getName(), null, page));
    }
    
    /**
     * 打开附魔菜单 - 显示该附魔的所有等级书
     */
    public void openEnchantMenu(Player player, AdvancedEnchantment enchant, AdvancedGroup group) {
        int levels = enchant.getLevels();
        int size = Math.max(9, ((levels - 1) / 9 + 1) * 9);
        size = Math.min(size, 54);
        
        String title = ENCHANT_MENU_TITLE_PREFIX + group.getColor() + enchant.getDisplayNoColor();
        title = ChatColor.translateAlternateColorCodes('&', title);
        if (title.length() > 32) title = title.substring(0, 32);
        
        Inventory inventory = Bukkit.createInventory(new AEAddonHolder(GUIType.ENCHANT_MENU, group.getName(), enchant.getPath(), 1), size, title);
        
        for (int level = 1; level <= levels && level <= size; level++) {
            ItemStack book = AEAPI.createEnchantmentBook(enchant.getPath(), level, 100, 0, player);
            if (book != null) {
                ItemMeta meta = book.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
                    lore.add("");
                    lore.add(ChatColor.translateAlternateColorCodes('&', "&7点击获取此附魔书"));
                    lore.add(ChatColor.translateAlternateColorCodes('&', "&7品质: " + group.getColor() + group.getDisplayName()));
                    meta.setLore(lore);
                    book.setItemMeta(meta);
                }
                inventory.setItem(level - 1, book);
            }
        }
        
        // 添加返回按钮
        if (size > 9) {
            inventory.setItem(size - 5, createNavigationItem(Material.ARROW, "&c返回品质菜单"));
        }
        
        // 填充空白
        fillEmptySlots(inventory, createGlassPane());
        
        player.openInventory(inventory);
        playerStates.put(player.getUniqueId(), new GUIState(GUIType.ENCHANT_MENU, group.getName(), enchant.getPath(), 1));
    }
    
    /**
     * 获取玩家的GUI状态
     */
    public GUIState getPlayerState(Player player) {
        return playerStates.get(player.getUniqueId());
    }
    
    /**
     * 清除玩家的GUI状态
     */
    public void clearPlayerState(Player player) {
        playerStates.remove(player.getUniqueId());
    }
    
    /**
     * 获取排序后的品质组列表
     */
    private List<AdvancedGroup> getSortedGroups() {
        List<AdvancedGroup> groups = new ArrayList<>();
        for (String groupName : AEAPI.getGroups()) {
            AdvancedGroup group = AdvancedGroup.matchGroup(groupName);
            if (group != null) {
                groups.add(group);
            }
        }
        groups.sort(Comparator.comparingInt(AdvancedGroup::getRarity));
        return groups;
    }
    
    /**
     * 获取指定品质组的所有附魔
     */
    private List<AdvancedEnchantment> getEnchantsByGroup(AdvancedGroup group) {
        List<AdvancedEnchantment> enchants = new ArrayList<>();
        for (String enchantName : AEAPI.getEnchantmentsByGroup(group.getName())) {
            AdvancedEnchantment enchant = AEAPI.getEnchantmentInstance(enchantName);
            if (enchant != null && enchant.isEnabled()) {
                enchants.add(enchant);
            }
        }
        return enchants;
    }
    
    /**
     * 创建品质组物品
     */
    private ItemStack createGroupItem(AdvancedGroup group) {
        ItemStack item = group.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(item.getType());
        }
        
        String displayName = ChatColor.translateAlternateColorCodes('&', group.getColor() + group.getDisplayName() + " &7附魔");
        meta.setDisplayName(displayName);
        
        // 存储品质组名称到PersistentDataContainer，用于点击时识别
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(groupKey, PersistentDataType.STRING, group.getName());
        
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7品质: " + group.getColor() + group.getDisplayName()));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7稀有度: " + group.getRarity()));
        
        int enchantCount = AEAPI.getEnchantmentsByGroup(group.getName()).size();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7附魔数量: &f" + enchantCount));
        lore.add("");
        lore.add(ChatColor.translateAlternateColorCodes('&', "&e点击浏览此品质的附魔"));
        
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    
    /**
     * 创建附魔物品
     */
    private ItemStack createEnchantItem(AdvancedEnchantment enchant, AdvancedGroup group) {
        // 使用附魔书作为图标
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(Material.ENCHANTED_BOOK);
        }
        
        String displayName = ChatColor.translateAlternateColorCodes('&', group.getColor() + enchant.getDisplayNoColor());
        meta.setDisplayName(displayName);
        
        // 存储附魔path到PersistentDataContainer，用于点击时识别
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(enchantKey, PersistentDataType.STRING, enchant.getPath());
        
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7描述: &f" + enchant.getDescription()));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7适用: &f" + enchant.getAppliesTo()));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7等级: &f1-" + enchant.getHighestLevel()));
        lore.add(ChatColor.translateAlternateColorCodes('&', "&7类型: &f" + String.join(", ", enchant.getTypes())));
        lore.add("");
        lore.add(ChatColor.translateAlternateColorCodes('&', "&e点击查看各等级附魔书"));
        
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    
    /**
     * 从物品的PersistentDataContainer获取附魔path
     */
    public String getEnchantPathFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.get(enchantKey, PersistentDataType.STRING);
    }
    
    /**
     * 从物品的PersistentDataContainer获取品质组名称
     */
    public String getGroupNameFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.get(groupKey, PersistentDataType.STRING);
    }
    
    /**
     * 创建导航物品
     */
    private ItemStack createNavigationItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * 创建信息物品
     */
    private ItemStack createInfoItem(String text) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', text));
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * 创建玻璃板
     */
    private ItemStack createGlassPane() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }
    
    /**
     * 填充空槽位
     */
    private void fillEmptySlots(Inventory inventory, ItemStack filler) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler.clone());
            }
        }
    }
    
    /**
     * GUI类型枚举
     */
    public enum GUIType {
        MAIN_MENU,
        GROUP_MENU,
        ENCHANT_MENU
    }
    
    /**
     * GUI状态类
     */
    public static class GUIState {
        private final GUIType type;
        private final String groupName;
        private final String enchantName;
        private final int page;
        
        public GUIState(GUIType type, String groupName, String enchantName, int page) {
            this.type = type;
            this.groupName = groupName;
            this.enchantName = enchantName;
            this.page = page;
        }
        
        public GUIType getType() {
            return type;
        }
        
        public String getGroupName() {
            return groupName;
        }
        
        public String getEnchantName() {
            return enchantName;
        }
        
        public int getPage() {
            return page;
        }
    }
    
    /**
     * GUI Holder
     */
    public static class AEAddonHolder implements InventoryHolder {
        private final GUIType type;
        private final String groupName;
        private final String enchantName;
        private final int page;
        
        public AEAddonHolder(GUIType type, String groupName, String enchantName, int page) {
            this.type = type;
            this.groupName = groupName;
            this.enchantName = enchantName;
            this.page = page;
        }
        
        public GUIType getType() {
            return type;
        }
        
        public String getGroupName() {
            return groupName;
        }
        
        public String getEnchantName() {
            return enchantName;
        }
        
        public int getPage() {
            return page;
        }
        
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}

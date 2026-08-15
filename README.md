# AEAddon

AEAddon 是一个用于修复 AdvancedEnchantments 插件在 Folia 服务端上运行时出现的兼容性问题的 Minecraft 插件。

## 问题背景

AdvancedEnchantments (AE) 官方尚未完全适配 Folia 服务端。Folia 采用**区域化线程模型（Region-based Threading）**，所有实体操作必须在实体所属的区域线程上执行，否则会抛出 `Thread failed main thread check` 错误或导致更隐蔽的状态同步问题。

由于无法直接修改 AE 的闭源代码，AEAddon 使用 **ByteBuddy + ASM** 技术在运行时动态修改 AE 的字节码，将不安全的实体操作替换为线程安全的版本。

---

## 修复的问题

### 1. 药水效果无法应用
**错误信息：**
```
Thread failed main thread check: Cannot add effects to entities asynchronously
```
**原因：** AE 在 `GlobalRegionScheduler` 上执行药水效果，但 `addPotionEffect` 必须在实体区域线程上执行。
**修复：** 通过 ASM 转换器将 `addPotionEffect` / `removePotionEffect` 调用替换为 `FoliaPotionHelper` 的线程安全版本。

### 2. 僵尸/生物假死（核心修复）
**现象：**
- 玩家使用某些附魔击杀僵尸或其他生物后，生物进入"假死"状态
- 客户端不断闪烁死亡动画，但玩家无法攻击到该生物
- 生物仍然可以正常移动和攻击玩家
- 服务端日志无异常，但实体状态明显不同步

**涉及的附魔：**
| 附魔名称 | 效果类型 | 触发条件 |
|---------|---------|---------|
| deathbringer | DOUBLE_DAMAGE | 攻击时概率触发双倍伤害 |
| planetarydeathbringer | INCREASE_DAMAGE | 攻击时增加伤害 |
| twinge | DO_HARM | 直接造成伤害 |
| demonforged | DO_HARM | 直接造成伤害 |
| devour | DO_HARM | 直接造成伤害 |
| rage | DO_HARM | 直接造成伤害 |

**根因分析：**

AE 的 `DamageHandler.damageIgnoringArmor()` 方法在计算最终伤害时，直接调用 `victim.setHealth(0)` 来击杀实体：

```java
// AE 原始代码（反编译）
if (remainingHealth < 0.0F) {
    victim.setHealth(0.0F);  // 直接设置生命值为0
    return true;
}
```

这种做法存在两个问题：

1. **绕过 Bukkit 事件系统**：`setHealth(0)` 不会触发 `EntityDamageEvent` 和 `EntityDeathEvent`，导致其他插件无法正常监听实体死亡事件
2. **Folia 状态不同步**：在 Folia 的多线程架构下，跨线程的 `setHealth(0)` 调用会导致服务端内部实体状态与客户端显示状态不一致。服务端认为实体已死亡（health=0），但没有正确触发实体移除流程，导致客户端播放死亡动画但实体仍存在且可交互

**修复方案：**

在 `FoliaPotionHelper.setHealth()` 中检测 health <= 0 的情况，改用 `damage(Integer.MAX_VALUE)` 来确保正常触发死亡事件流程：

```java
private static void setHealthInternal(LivingEntity entity, double health) {
    double maxHealth = entity.getMaxHealth();
    double targetHealth = Math.min(health, maxHealth);

    // 关键修复：当设置生命值为0（击杀实体）时，
    // 使用 damage() 替代 setHealth() 来触发正确的 Bukkit 事件。
    // 直接 setHealth(0) 绕过 EntityDamageEvent 和 EntityDeathEvent，
    // 导致 Folia 实体状态不同步（僵尸假死 Bug）。
    if (targetHealth <= 0.0 && !entity.isDead() && entity.getHealth() > 0.0) {
        entity.damage(Integer.MAX_VALUE);
        return;
    }

    entity.setHealth(targetHealth);
}
```

`damage()` 方法会正确触发以下事件链：
```
EntityDamageEvent -> EntityDeathEvent -> 实体移除
```

这确保了 Folia 服务端能够正确同步实体状态，彻底消除假死现象。

### 3. 增伤/伤害效果无法触发
**错误信息：**
```
Thread failed main thread check: Accessing entity state off owning region's thread
```
**原因：** `DamageHandler` 中的 `getHealth()`、`isDead()`、`damage()` 等方法在错误的线程上执行。
**修复：** 扩展 ASM 转换器覆盖 `DamageHandler` 类，替换所有实体状态访问方法。

### 4. 移动速度/飞行/传送等效果失效
**原因：** `setWalkSpeed`、`setFlying`、`teleport` 等操作需要在实体区域线程执行。
**修复：** 添加对这些方法的 ASM 转换支持。

---

## 技术实现

### 架构

```
AEAddon
├── AEAddon.java                  # 插件主类，初始化所有组件
├── foliafix/
│   ├── AEFoliaByteBuddyPatcher   # ByteBuddy 动态 Agent 入口
│   ├── ApplyPotionEffectTransformer  # ASM ClassFileTransformer
│   ├── FoliaPotionHelper         # 线程安全实体操作帮助类
│   ├── AEFoliaSchedulerHelper    # Folia 调度器代理
│   └── AEFoliaFixListener        # 事件监听器（备用修复层）
└── ...
```

### 工作流程

1. **插件启动**：`AEAddon.onEnable()` 检测是否运行在 Folia 上
2. **初始化转换器**：`AEFoliaByteBuddyPatcher.init()` 通过 ByteBuddy 附加到 JVM
3. **注册 Transformer**：`ApplyPotionEffectTransformer` 作为 `ClassFileTransformer` 注册
4. **批量重转换**：对已加载的 AE 类进行批量重转换
5. **运行时拦截**：对于未加载的类，转换器在类加载时自动生效
6. **字节码替换**：`EffectMethodVisitor` 遍历方法字节码，将不安全的实体操作替换为 `FoliaPotionHelper` 的静态方法调用

### 转换的目标类

ASM 转换器会拦截以下 AE 类：

- **效果类**：`net.advancedplugins.ae.impl.effects.effects.effects.internal.*`
  - `DoHarmEffect`、`KillEffect`、`DoubleDamageEffect`、`RemoveHealthDamageEffect` 等
- **伤害处理**：`net.advancedplugins.ae.impl.effects.effects.actions.handlers.DamageHandler`
- **调度器**：`net.advancedplugins.ae.impl.utils.FoliaScheduler`

### 替换的方法调用

| 原始方法 | 替换为 | 说明 |
|---------|--------|------|
| `LivingEntity.addPotionEffect()` | `FoliaPotionHelper.addPotionEffect()` | 线程安全药水应用 |
| `LivingEntity.removePotionEffect()` | `FoliaPotionHelper.removePotionEffect()` | 线程安全药水移除 |
| `Player.setWalkSpeed()` | `FoliaPotionHelper.setWalkSpeed()` | 线程安全速度设置 |
| `Player.setFlying()` | `FoliaPotionHelper.setFlying()` | 线程安全飞行状态 |
| `Entity.teleport()` | `FoliaPotionHelper.teleport()` | 线程安全传送 |
| `LivingEntity.setHealth()` | `FoliaPotionHelper.setHealth()` | **含假死修复** |
| `LivingEntity.damage()` | `FoliaPotionHelper.damage()` | 线程安全伤害 |
| `LivingEntity.getHealth()` | `FoliaPotionHelper.getHealth()` | 非阻塞缓存读取 |
| `LivingEntity.isDead()` | `FoliaPotionHelper.isDead()` | 非阻塞缓存读取 |
| `Bukkit.getScheduler()` | `AEFoliaSchedulerHelper.getScheduler()` | Folia 调度器代理 |

### 线程安全机制

`FoliaPotionHelper` 使用以下机制确保线程安全：

1. **线程检测**：通过 `isOwnedByCurrentRegion()` 检查当前线程是否拥有实体
2. **线程调度**：如果不在正确线程，使用 `RegionScheduler.execute()` 将操作调度到实体所属区域
3. **缓存优化**：对于频繁读取的操作（如 `getHealth()`），使用 ConcurrentHashMap 缓存避免阻塞
4. **假死修复**：`setHealth()` 在 health <= 0 时自动切换到 `damage()` 路径

---

## 使用说明

### 环境要求

| 项目 | 要求 |
|------|------|
| 服务端 | Folia 1.20.1+ / Paper 1.20.1+（向后兼容） |
| 依赖插件 | AdvancedEnchantments 9.22.8+（Folia 修改版） |
| Java | 17+ |

### 安装 AEAddon

1. 将 `AEAddon-1.0.jar` 放入服务器的 `plugins` 文件夹
2. 确保已安装 AdvancedEnchantments 插件
3. **修改 AE 插件的 plugin.yml（重要）**
   
   Folia 服务端要求插件在 `plugin.yml` 中显式声明支持 Folia，否则插件将无法加载。由于 AE 官方未适配 Folia，需要手动修改：
   
   **步骤：**
   1. 找到服务器 `plugins` 目录下的 `AdvancedEnchantments-9.24.10-bm.jar`（或你使用的版本）
   2. 将 `.jar` 文件当作压缩包打开（使用 7-Zip、WinRAR 或 Bandizip 等工具）
   3. 找到压缩包内的 `plugin.yml` 文件，将其解压到桌面
   4. 用文本编辑器打开 `plugin.yml`，在文件任意位置（建议放在 `api-version` 下方）添加以下内容：
      ```yaml
      folia-supported: true
      ```
      修改后的 `plugin.yml` 示例如下：
      ```yaml
      name: AdvancedEnchantments
      version: 9.24.10
      main: net.advancedplugins.ae.Core
      api-version: "1.13"
      folia-supported: true
      
      description: "The most extensive custom enchantments plugin up to date"
      # ... 其余内容保持不变
      ```
   5. 保存修改后的 `plugin.yml`
   6. 将修改后的 `plugin.yml` 拖回压缩包中，覆盖原文件
   7. 关闭压缩包工具
   
   **注意事项：**
   - 修改前建议备份原始 `.jar` 文件
   - 确保使用支持直接编辑压缩包内文件的工具（7-Zip 免费且推荐）
   - 不要解压整个 jar，只需替换 `plugin.yml` 这一个文件

4. 启动服务器
5. 检查控制台输出确认加载成功：
   ```
   [AEAddon] 检测到Folia服务端，使用Folia调度器
   [AEAddon] ByteBuddy补丁已注册，AE类加载时将自动转换
   [AEAddon-FoliaFix] FoliaEntityHelper initialized
   ```

### 验证修复生效

在控制台查看以下输出确认转换成功：
```
[AEAddon-FoliaFix] Retransform complete: XXX success, X skipped
[AEAddon-FoliaFix] Transformer stats: transformCalls=XX, schedulerPatches=XX, ...
```

### 命令

- `/aeaddon` 或 `/aea` - 打开附魔书管理 GUI

---

## 构建

### 环境准备
- JDK 17+
- Maven 3.8+

### 构建命令

```bash
mvn clean package -DskipTests
```

生成的 jar 文件位于 `target/AEAddon-1.0.jar`

---

## 注意事项

2. 更新 AE 插件版本后可能需要更新 AEAddon 的 ASM 转换目标类列表
3. 由于使用了字节码修改技术，某些情况下可能与其他修改字节码的插件冲突
4. 如果服务器不是 Folia，插件会自动回退到普通 Bukkit 行为，不会产生副作用

## 已知限制

- 部分 AE 命令（如 `/ae`）在 Folia 上可能存在兼容性问题，但不影响核心功能
- 极端情况下 ASM 转换可能失败，插件会自动回退到原始行为
- 假死修复仅对通过 ASM 转换拦截到的 `setHealth()` 调用生效

---

## 故障排查

### 假死问题仍然存在
1. 确认控制台有 `[AEAddon-FoliaFix] Retransform complete` 输出
2. 检查 `Transformer stats` 中 `transformCalls` 是否大于 0
3. 确认 AE 版本与转换目标类匹配（查看 `ApplyPotionEffectTransformer.ENTITY_TARGET_CLASSES`）

### 转换失败
1. 检查 Java 版本是否为 17+
2. 确认 ByteBuddy Agent 有权限附加到 JVM
3. 查看控制台详细错误日志

---

## 技术栈

- **ByteBuddy**：运行时字节码修改
- **ASM 9**：底层字节码操作
- **Folia API**：区域化线程调度
- **Bukkit API**：Minecraft 插件接口

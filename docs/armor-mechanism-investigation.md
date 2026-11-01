# ArmorRestoreMod 源码调查报告

> 目标：在 Minecraft Java Edition 26.2（NeoForge 26.2.x）上，以 1.8.9 行为为规范，**只恢复 1.9 战斗更新之前的护甲伤害减免机制**，同时最大限度保持与现代 Minecraft 及其他 Mod 的兼容性。
>
> 本报告只做调查与方案分析，不包含实现代码。
>
> **版本说明**：调查基于 26.2 完成，项目随后移植到 **26.3**。26.3 的伤害管线与扩展点与 26.2 逐行相同（仅行号偏移），因此文中结论继续有效；移植细节见 `implementation-contract.md` 开头的变更记录。

---

## 0. 调查方法与资料说明

| 资料 | 形态 | 使用方式 |
|---|---|---|
| `reference/minecraft-26.2-source/` | 无混淆 **.class 文件**（约 11000 个，无 .java） | 用 CFR 0.152（JDK 21 运行）反编译到 `decomp262/`（6707 个 .java），以此为 26.2 原版行为依据；关键方法另用 `javap -c` 核对字节码 |
| `reference/minecraft-1.8.9-source/` | MCP 反混淆真实 .java 源码 | 直接阅读 |
| NeoForge 26.2 | 本地 `docs/` 仅有文档站快照（无 26.2 API 细节） | 直接拉取官方仓库 `neoforged/NeoForge` 分支 **`26.2.x`** 的 patch 与源码（`patches/net/minecraft/world/entity/LivingEntity.java.patch`、`DamageContainer.java`、`LivingIncomingDamageEvent.java`、`LivingDamageEvent.java`、`ArmorHurtEvent.java`、`LivingShieldBlockEvent.java`、`CommonHooks.java` 等），存于 `neoforge262/` |
| `reference/wiki/` | **目录为空**（未提供内容） | 本报告全部结论来自源码验证，未依赖 Wiki |

> 注：本报告写作时 `mod/` 目录为空，未创建任何实现代码。
>
> **关于文中出现的本地路径**：`decomp262/`、`neoforge262/`、`reference/`、`tools/` 是本次调查在工作区内使用的临时目录，**不随本仓库发布**——其中包含反编译的 Minecraft 源码与第三方构件，重新分发会侵犯版权。若需复核文中任何结论，可按第 0 节的资料清单自行生成：用 CFR 反编译你自己的 Minecraft 版本 jar，从 `neoforged/NeoForge` 仓库对应分支拉取 patch。所有行号均以当时使用的版本为准，版本不同会有偏移。

---

## A. Minecraft 26.2 完整伤害处理流程

以下行号来自 `decomp262/net/minecraft/world/entity/LivingEntity.java`（CFR 反编译）与 `decomp262/net/minecraft/world/entity/player/Player.java`。所有伤害均在服务端逻辑（`hurtServer`）中处理。

### A.1 主流程（LivingEntity#hurtServer, L1141）

```
攻击者侧（如 Player#attack L2726 附近）
  └─ EnchantmentHelper.modifyDamage / getEnchantedDamage（攻击方附魔，如锋利）
  └─ target.hurtServer(level, damageSource, postEnchantmentDamage)   ← 伤害进入

LivingEntity#hurtServer(ServerLevel, DamageSource source, float damage)  L1141
  1. isInvulnerableTo(level, source)                    L1145
     └─ isInvulnerableToBase(source) + EnchantmentHelper.isImmuneToDamage(...)   L3768
        （附魔免疫，如冰霜行者式免疫；NeoForge 在此层有 EntityInvulnerabilityCheckEvent）
  2. isDeadOrDying() → false                             L1148
  3. IS_FIRE 且有 FIRE_RESISTANCE 效果 → false           L1151
  4. 睡眠唤醒、noActionTime 清零、damage<0 归零
  5. 【Shield】applyItemBlocking(level, source, damage)   L1163 → 实现在 L1259
     ├─ getItemBlockingWith()（使用中的盾牌）+ DataComponents.BLOCKS_ATTACKS
     ├─ bypassedBy 标签过滤、穿透箭(>0)免疫
     ├─ 视角夹角计算 → BlocksAttacks.resolveBlockedDamage(source, damage, angle)
     ├─ NeoForge：此处触发 LivingShieldBlockEvent（CommonHooks.onDamageBlock）
     └─ 返回 damageBlocked；damage -= damageBlocked
  6. IS_FREEZING ×5（对特定生物）                        L1166
  7. DAMAGES_HELMET（铁砧/落块/钟乳石）→ hurtHelmet（头盔耐久）+ damage×0.75   L1169
  8. NaN/Infinity → Float.MAX_VALUE                      L1173
  9. 无敌帧分支                                          L1177
     ├─ invulnerableTime > 10 且非 BYPASSES_COOLDOWN：
     │    damage <= lastHurt → return false
     │    actuallyHurt(level, source, damage - lastHurt)（只结算差值）
     └─ 否则：actuallyHurt(level, source, damage)（全额），lastHurt=damage, invulnerableTime=20
 10. 侧效果：BlocksAttacks.onBlocked / broadcastDamageEvent、markHurt、击退 dealDefaultKnockback L1202
 11. 死亡处理：checkTotemDeathProtection（图腾）→ die(source)   L1206
 12. MobEffectInstance.onMobHurt 循环（效果对受伤的反应，如 Infested 寄生虫）  L1222
 13. CriteriaTriggers（进度）、盾牌格挡统计
```

### A.2 结算核心（actuallyHurt, L1825；Player 覆写于 Player.java L724，逻辑相同 + 饱食消耗）

```
actuallyHurt(ServerLevel, DamageSource source, float dmg)
  0. isInvulnerableTo 再检查                              L1827
  1. 【Armor】dmg = getDamageAfterArmorAbsorb(source, dmg)     L1830 → L1779
     ├─ if (!source.is(BYPASSES_ARMOR)):
     │    ├─ hurtArmor(source, dmg)   ← 护甲耐久伤害；Player 实现调 doHurtEquipment(4 件套)  L714
     │    └─ dmg = CombatRules.getDamageAfterAbsorb(this, dmg, source, getArmorValue(), ARMOR_TOUGHNESS)
  2. 【Enchantment + Mob Effect】dmg = getDamageAfterMagicAbsorb(source, dmg)  L1831 → L1787
     ├─ BYPASSES_EFFECTS（starve）→ 直接返回
     ├─ 【Mob Effect·抗性】RESISTANCE 且非 BYPASSES_RESISTANCE：
     │    dmg = max(dmg × (25 - (amplifier+1)×5) / 25, 0)   （每级 -20%）
     ├─ dmg <= 0 → return 0
     ├─ BYPASSES_ENCHANTMENTS（sonic_boom）→ 直接返回
     └─ 【Enchantment】epf = EnchantmentHelper.getDamageProtection(serverLevel, this, source)
          if (epf > 0) dmg = CombatRules.getDamageAfterMagicAbsorb(dmg, epf)
          = dmg × (1 - clamp(epf, 0, 20)/25)                （EPF 上限 20 → 最高 -80%）
  3. 【Absorption】                                        L1832
     ├─ originalDamage = dmg（ armor+附魔后 ）
     ├─ dmg = max(dmg - getAbsorptionAmount(), 0)
     ├─ setAbsorptionAmount(absorption - (original - dmg))  （吸收池扣减）
  4. dmg == 0 → return（生命值无损）
  5. CombatTracker.recordDamage、setHealth(health - dmg)   L1842-1843
  6. setAbsorptionAmount(absorption - dmg)  ← 字节码确认存在，但数学上恒为 0，惰性代码
  7. gameEvent(ENTITY_DAMAGE)
```

**顺序总结**（与题目要求对应）：

| 步骤 | 位置 |
|---|---|
| 1. DamageSource 进入 | `LivingEntity#hurtServer` L1141（调用方：`Player#attack`/投射物/环境 tick 等） |
| 2. 免疫/无敌帧 | `isInvulnerableTo` L1145、无敌帧分支 L1177 |
| 3. Shield | `applyItemBlocking` L1259（在 armor 之前） |
| 4. Armor | `actuallyHurt` → `getDamageAfterArmorAbsorb` L1779 → `CombatRules.getDamageAfterAbsorb` |
| 5. Enchantment | `getDamageAfterMagicAbsorb` L1787 → `EnchantmentHelper.getDamageProtection` → `CombatRules.getDamageAfterMagicAbsorb` |
| 6. Mob Effect（抗性） | 同上方法内，附魔**之前**（L1797） |
| 7. Absorption | `actuallyHurt` L1832-1833 |
| 8. 最终生命值 | `setHealth(getHealth() - dmg)` L1843 |

> 注意：26.2 中抗性（Mob Effect）和附魔同在 `getDamageAfterMagicAbsorb`，**抗性先于附魔**；1.8.9 中两者同在 `applyPotionDamageCalculations`，顺序同为抗性→附魔。两代一致。

---

## B. 现代 armor reduction 的计算位置

### B.1 真正执行减伤的方法

**类**：`net.minecraft.world.damagesource.CombatRules`（原版类，`reference/minecraft-26.2-source/net/minecraft/world/damagesource/CombatRules.class`）
**方法**：`getDamageAfterAbsorb(LivingEntity victim, float damage, DamageSource source, float totalArmor, float armorToughness) → float`

26.2 反编译源码（完整）：

```java
public static final float MAX_ARMOR = 20.0f;
public static final float ARMOR_PROTECTION_DIVIDER = 25.0f;
public static final float BASE_ARMOR_TOUGHNESS = 2.0f;
public static final float MIN_ARMOR_RATIO = 0.2f;

public static float getDamageAfterAbsorb(LivingEntity victim, float damage, DamageSource source,
                                         float totalArmor, float armorToughness) {
    float toughness = 2.0f + armorToughness / 4.0f;
    // 伤害越大，护甲的有效值被“穿透”得越多；下限为总护甲的 20%
    float realArmor = Mth.clamp(totalArmor - damage / toughness, totalArmor * 0.2f, 20.0f);
    float armorFraction = realArmor / 25.0f;
    ItemStack weaponItem = source.getWeaponItem();
    if (weaponItem != null && victim.level() instanceof ServerLevel serverLevel) {
        // 1.21+ 新增：Breach（破甲）附魔通过武器降低护甲有效性（-15%/级）
        armorFraction = Mth.clamp(EnchantmentHelper.modifyArmorEffectiveness(
                serverLevel, weaponItem, victim, source, armorFraction), 0.0f, 1.0f);
    }
    return damage * (1.0f - armorFraction);
}

public static float getDamageAfterMagicAbsorb(float damage, float totalMagicArmor) {
    float realArmor = Mth.clamp(totalMagicArmor, 0.0f, 20.0f);
    return damage * (1.0f - realArmor / 25.0f);   // 附魔 EPF，上限 20 → -80%
}
```

**输入**：`damage`（格挡后、护甲前伤害）、`totalArmor = getArmorValue() = floor(ARMOR 属性值)`（`LivingEntity` L1756）、`armorToughness = ARMOR_TOUGHNESS 属性值`、伤害源（用于取攻击者武器触发 Breach）。
**输出**：护甲后伤害（float）。

**调用链**：

```
hurtServer → actuallyHurt → LivingEntity#getDamageAfterArmorAbsorb(L1779, protected)
    └─ CombatRules.getDamageAfterAbsorb
        └─ EnchantmentHelper.modifyArmorEffectiveness（Breach，数据驱动）
Player#actuallyHurt(L724) → 同一条链（Player 未改公式，仅多了饱食/统计）
```

**armor 值来源**：`Attributes.ARMOR`（范围 0–30）、`Attributes.ARMOR_TOUGHNESS`（0–20），由盔甲物品的 `EQUIPPABLE`/`AttributeModifiers` 数据组件与生物的属性 JSON 提供。

### B.2 NeoForge DamageContainer / Event 的包裹关系（`26.2.x` 分支 patch）

NeoForge 对 `LivingEntity` 的 patch（`neoforge262/LivingEntity.java.patch`）：

1. 新增字段 `protected Stack<DamageContainer> damageContainers`（在 `hurtServer` 入口 push，出口 pop）。
2. `hurtServer` 流程改写：
   - push 容器后**立刻**触发 `LivingIncomingDamageEvent`（`CommonHooks.onEntityIncomingDamage`，L326）；取消 = 本次伤害整体作废。
   - `damage = container.getNewDamage()`（容器为伤害数值的唯一真源）。
   - 盾牌：`LivingShieldBlockEvent`（`CommonHooks.onDamageBlock`），结果写入容器的 blockedDamage。
   - 无敌帧差值分支：`setReduction(INVULNERABILITY, lastHurt)`。
   - 出口：`onDamageTaken(container)` + `CommonHooks.onLivingDamagePost`（`LivingDamageEvent.Post`）→ pop。
3. **`actuallyHurt` 被改写**（关键）：

```java
// Neo 版 actuallyHurt（patch 内容，节选）
container.setReduction(Reduction.ARMOR,
        container.getNewDamage()
        - this.getDamageAfterArmorAbsorb(source, container.getNewDamage()));   // ← 原版护甲照常执行
this.getDamageAfterMagicAbsorb(source, container.getNewDamage());              // ← 原版抗性+附魔照常执行，
                                                                               //   结果写入容器
float damage = CommonHooks.onLivingDamagePre(this, container);                 // ← LivingDamageEvent.Pre
Preconditions.checkArgument(!this.dead, "...");                                // Pre 中杀死实体非法
container.captureInflictedDamage();
container.setReduction(Reduction.ABSORPTION, Math.min(getAbsorptionAmount(), damage));
...
if (newDamage != 0) { recordDamage; setHealth(health - newDamage); }           // 吸收池由容器管理
```

4. `doHurtEquipment`（护甲耐久）被改为：`CommonHooks.onArmorHurt(...)` → **`ArmorHurtEvent`**（可取消、可逐槽修改耐久伤害），原版循环作废。

**DamageContainer 要点**（`neoforge262/DamageContainer.java`）：
- `Reduction` 枚举：`INVULNERABILITY / ARMOR / ENCHANTMENTS / MOB_EFFECTS / ABSORPTION / INNATE_RESISTANCE`。
- `setReduction(type, amount)`：先跑已注册的 `IReductionFunction` 链（`modify(container, vanillaReduction) → 新减伤值`），再 `newDamage = max(newDamage - 减伤, 0)`。
- `addModifier(type, fn)`：**只能在 `LivingIncomingDamageEvent` 中注册**（此时减伤尚未计算）。
- `setNewDamage()`：直接设置当前伤害值；`getOriginalDamage()` 返回入口伤害。

### B.3 结论（对问题 C 的直接回答）

> **“仅使用 NeoForge Event 是否可以完整替换现代 armor reduction，而不让现代 armor reduction 先执行？”**

**不能做到“不让它先执行”，但可以做到“行为上完整替换”。** 理由：

- 原版护甲计算（`getDamageAfterArmorAbsorb` → `CombatRules.getDamageAfterAbsorb`）硬编码在 NeoForge 重写后的 `actuallyHurt` 中、`LivingDamageEvent.Pre` **之前**无条件执行；`LivingIncomingDamageEvent` 虽然更早，但只能取消整个伤害或改伤害数值，**没有“跳过护甲步骤”的开关**。
- NeoForge 未 patch `CombatRules`（该文件 404 无 patch），也没有针对“护甲公式”的独立事件。
- **但是**：原版护甲执行后、生命值扣减前的 `LivingDamageEvent.Pre` 中，`container.setNewDamage()` 对最终伤害有完全权威性；配合：
  - `DamageContainer.addReductionModifier(Reduction.ARMOR/ENCHANTMENTS, fn)`（在 Incoming 事件中注册，可将原版记入容器的减伤值替换为任意值——最终 `newDamage` 由这些减伤推出）；
  - `ArmorHurtEvent`（控制护甲耐久）；
  
  可以在不写 Mixin 的前提下，让最终扣血、容器数值、护甲耐久全部呈现 1.8.9 行为。原版护甲计算只是“白算了一次”，唯一可见副作用是少量统计数值（如 `DAMAGE_RESISTED`）按原版中间值记录，以及容器中未替换的减伤值——前者纯装饰，后者可通过替换减伤函数解决。
- `LivingDamageEvent.Post` 完全只读（快照），不能用于修改。

---

## C. NeoForge 26.2 可用扩展点清单

| 扩展点 | 时机（相对原版护甲） | 能力 | 对本 Mod 的价值 |
|---|---|---|---|
| `EntityInvulnerabilityCheckEvent` | `isInvulnerableTo` 内，最早 | 改免疫判定 | 低 |
| **`LivingIncomingDamageEvent`** | push 容器后、盾/护甲前 | 取消伤害、`setAmount`、`setInvulnerabilityTicks`、**`addReductionModifier(Reduction, IReductionFunction)`** | ★★★ 唯一能注册减伤替换函数的位置 |
| `LivingShieldBlockEvent` | 盾牌结算处（护甲前） | 改格挡伤害/盾耐久/取消格挡 | 不涉及（保持现代盾牌） |
| 原版护甲+抗性+附魔执行 | — | — | 白算一次，无可见副作用（见 B.3） |
| **`ArmorHurtEvent`** | `hurtArmor → doHurtEquipment`（护甲减伤计算内部） | 取消或逐槽改护甲耐久伤害 | ★★ 1.8.9 与现代耐久规则几乎一致，默认无需动；预留 |
| **`LivingDamageEvent.Pre`** | 护甲/抗性/附魔之后、吸收与扣血之前 | `setNewDamage()` 权威覆盖最终伤害 | ★★ 备选注入点/兜底 |
| `LivingDamageEvent.Post` | 扣血之后 | 只读快照（含各 Reduction 终值） | 调试/统计用 |
| `LivingKnockBackEvent`、`LivingDeathEvent` 等 | — | 与护甲无关 | 不涉及 |
| 数据驱动：`EQUIPPABLE.damageOnHurt`、`BLOCKS_ATTACKS`、附魔 `damage_protection`/`armor_effectiveness` 效果、`Attributes.ARMOR/ARMOR_TOUGHNESS` | — | 内容侧定义 | 兼容性考虑项 |

`CommonHooks` 相关入口（`neoforge262/CommonHooks.java`）：`onEntityIncomingDamage` L326、`onLivingDamagePre` L352、`onLivingDamagePost` L365、`onArmorHurt` L381、`onDamageBlock` L1201。

其他 NeoForge 事实：
- `CombatRules.java`、`ArmorMaterials.java` **无 NeoForge patch**（raw 404）；`EnchantmentHelper.java`、`ItemStack.java` 有小 patch（与伤害管线无关）。
- `Player#actuallyHurt` 的 patch 与 `LivingEntity` 完全镜像（patch 注释 "Keep this in-sync"），两条路径都会经过同一套事件。
- 事件均为**服务端**触发（`hurtServer` 全在服务端），多人一致性由服务器保证，客户端只是血量同步显示。

---

## D. 1.8.9 旧护甲机制（源码验证）

以下行号来自 `reference/minecraft-1.8.9-source/src/minecraft/net/minecraft/`。

### D.1 护甲值获取（无属性系统）

- `EntityLivingBase#getTotalArmorValue`（L1192）/ `InventoryPlayer#getTotalArmorValue`（L710）：遍历 4 个护甲槽，累加 `ItemArmor.damageReduceAmount`（`ItemArmor.java` L66、L82：由材质表定义）。**1.8.9 没有护甲属性（SharedMonsterAttributes 无 armor/toughness，已验证）**，也不随耐久度衰减。
- 材质表（`ItemArmor.ArmorMaterial`，L237-241）：LEATHER 5 [1,3,2,1]、CHAIN [1,4,5,2]、IRON 15 [2,6,5,2]、GOLD 7 [2,5,3,1]、DIAMOND 33 [3,8,6,3] —— 与 26.2 `ArmorMaterials` 的每件防御值完全相同（26.2 额外有 COPPER、TURTLE、ARMADILLO 等）。

### D.2 旧护甲减伤公式

`EntityLivingBase#applyArmorCalculations`（L1215）：

```java
protected float applyArmorCalculations(DamageSource source, float damage) {
    if (!source.isUnblockable()) {
        int i = 25 - this.getTotalArmorValue();   // 无 toughness、无下限 clamp、无上限公式 cap
        float f = damage * (float)i;
        this.damageArmor(damage);                 // 耐久伤害用“护甲前”伤害计算
        damage = f / 25.0F;
    }
    return damage;
}
```

即 **`伤害 × (25 - 护甲值) / 25`，每点护甲 = 4% 减伤**。原版装备上限护甲 20（-80%）。公式本身没有 clamp（护甲 ≥25 会变成 0/负伤害，原版不可达；这是移植时要做的设计决策，见 F.4）。1.9 新增的 toughness 与下限 `totalArmor×0.2` 在 1.8.9 中完全不存在。

### D.3 保护类附魔（EPF）

`applyPotionDamageCalculations`（L1231）中：

```java
int k = EnchantmentHelper.getEnchantmentModifierDamage(this.getInventory(), source); // 全部护甲槽求和
if (k > 20) k = 20;                       // 实体侧二次 cap：EPF ≤ 20 → 最高 80%
if (k > 0 && k <= 20) damage = damage * (25 - k) / 25.0F;
```

`EnchantmentHelper#getEnchantmentModifierDamage`（L197）：EPF 总和 clamp 到 [0,25] 后**服务端随机化**：

```java
return (epf + 1 >> 1) + enchantmentRand.nextInt((epf >> 1) + 1);
// 结果 ∈ [⌈epf/2⌉, epf]，即实际减伤在 50%~100% 的 EPF 间浮动
```

`EnchantmentProtection#calcModifierDamage`（L73）——**EPF 单件值**：

```java
f = (6 + level²) / 3.0F;
protection:      floor(f × 0.75)   // 任意伤害；IV 每件 = floor(7.33×0.75) = 5
fire_protection: floor(f × 1.25)   // 仅 source.isFireDamage()
feather_falling: floor(f × 2.5)    // 仅 source == DamageSource.fall（对象同一性比较）
blast_protection:floor(f × 1.5)    // 仅 isExplosion()
projectile_prot: floor(f × 1.5)    // 仅 isProjectile()
canHarmInCreative()（= outOfWorld）→ 0
```

对比现代（26.2 数据驱动，确定性、无随机化）：protection **1/级**、fire/blast/projectile **2/级**、feather_falling **3/级**，条件用伤害标签（is_fire/is_fall/is_explosion/is_projectile）。**旧保护 IV 每件 5 EPF vs 现代 4 EPF，且旧版有随机化** —— 这两代差异必须重算 EPF 才能消除。

### D.4 抗性效果（Mob Effect）

`applyPotionDamageCalculations` L1239：`damage × (25 - (amplifier+1)×5)/25`，即每级 -20%；`outOfWorld` 绕过抗性；`isDamageAbsolute()`（只有 starve）绕过抗性+附魔。与 26.2 抗性公式数值一致（26.2 的绕过标签：`bypasses_resistance = [out_of_world, generic_kill]`、`bypasses_effects = [starve]`）。

### D.5 护甲耐久与护甲值的关系

- **耐久不改变护甲值**（`getTotalArmorValue` 与耐久无关）。
- `InventoryPlayer#damageArmor`（L729）：**4 件全部各扣 `max(1, 护甲前伤害/4)` 点耐久**，与槽位无关；仅 `ItemArmor` 实例受损。
- 特例：铁砧/落块砸中戴头盔者（`attackEntityFrom` L887）：头盔扣 `amount×4 + rand×amount×2` 耐久，伤害 ×0.75（现代等价：DAMAGES_HELMET 标签，伤害 ×0.75 相同，但耐久为 `max(1, dmg/4)`，数值不同）。

### D.6 哪些 DamageSource 可被护甲防御

`DamageSource.java` L14-27：`setDamageBypassesArmor()`（= isUnblockable）的来源：**onFire、inWall、drown、starve、fall、outOfWorld、generic、magic、wither、indirectMagic**。即：**inFire（火焰接触）、lava、hotFloor、cactus、anvil、fallingBlock、攻击、爆炸、投射物可被护甲防御**；着火 tick/摔落/溺水/饥饿/虚空/凋零/魔法不可。

对比 26.2 `bypasses_armor` 标签（on_fire、in_wall、cramming、drown、fly_into_wall、generic、wither、dragon_breath、starve、fall、ender_pearl、freeze、stalagmite、magic、indirect_magic、out_of_world、generic_kill、sonic_boom、outside_border）：**两代对共有伤害源的护甲过滤完全一致**（lava/in_fire/cactus/anvil 都不绕过护甲；on_fire/fall/drown 都绕过）。差异仅在 26.2 的新增类型（freeze、stalagmite、sonic_boom 等默认绕过护甲）。

### D.7 顺序与格挡、吸收

1.8.9 `EntityPlayer#damageEntity`（L1156）顺序：

```
剑格挡（isBlocking = 用 EnumAction.BLOCK 的物品，1.8.9 只有剑；无盾牌物品类）
  damage = (1 + damage) × 0.5      （非 isUnblockable 时）
→ 护甲 applyArmorCalculations
→ 抗性+附魔 applyPotionDamageCalculations
→ 吸收池扣减 → 饱食消耗 → setHealth
```

无敌帧：`hurtResistantTime > maxHurtResistantTime/2`（=10/20 tick）时只结算差值 —— 与 26.2 的 `invulnerableTime > 10` 一致。

**关键差异**：1.8.9 没有盾牌物品（`ItemShield` 不存在，已验证），格挡是剑格挡 `(1+伤害)×0.5`；26.2 是数据组件盾牌（默认 100% 格挡 + 防具禁用计时）。本 Mod 目标只覆盖“护甲伤害减免”，**格挡行为保持 26.2 原样**（两者都在护甲之前发生，对护甲层无影响）。

---

## E. 1.8.9 vs 26.2 护甲机制差异总表

| 维度 | 1.8.9（旧规范） | 26.2（现代） | 差异评估 |
|---|---|---|---|
| armor attribute | **无属性系统**，直接累加 `ItemArmor.damageReduceAmount`（上限 20=全套钻石） | `Attributes.ARMOR`（0–30），盔甲数据组件提供修饰符；Mod 通过属性加成 | ★ 影响实现方式：新 Mod 盔甲只存在于属性系统，必须读属性值 |
| armor toughness | **不存在** | `Attributes.ARMOR_TOUGHNESS`（0–20），参与公式 | ★ 旧机制恢复 = 公式中忽略该属性 |
| armor damage reduction | `dmg × (25 - armor)/25`，每点 4%，无下限穿透 | `dmg × (1 - clamp(armor - dmg/(2+tough/4), armor×0.2, 20)/25)`，含穿透与 20% 下限 | ★★ 核心差异，需整体替换 |
| armor durability | 4 件各扣 `max(1, 护甲前伤害/4)`；护甲值与耐久无关 | 相同公式（`doHurtEquipment`），但 `damageOnHurt` 标志可关、任意 EQUIPPABLE 物品均可损 | ≈ 一致，兼容即可 |
| enchantment protection | EPF：protection `floor((6+lv²)/3×0.75)`（IV=5/件）、fire ×1.25、fall ×2.5、blast/projectile ×1.5；总和 clamp 25 → **服务端随机 [⌈EPF/2⌉, EPF]** → 实体 cap 20 | 数据驱动 `damage_protection`：protection 1/级、其余 2/级、摔落缓冲 3/级；**确定性**，cap 20 | ★★ 数值与随机性都不同，需重算 |
| damage source filtering | `isUnblockable` 白名单（onFire/inWall/drown/starve/fall/outOfWorld/generic/magic/wither/indirectMagic 绕过） | `bypasses_armor` 标签，共有伤害源集合一致；新增类型默认绕过 | ≈ 基本一致，可直接沿用现代标签 |
| shield interaction | 无盾牌物品；剑格挡 `(1+伤害)×0.5`，护甲前 | 盾牌物品（BLOCKS_ATTACKS 组件），默认 100% 格挡+禁用计时，护甲前；NeoForge 有 `LivingShieldBlockEvent` | 目标外，保持现代 |
| absorption interaction | 结算链末尾（附魔后），独立吸收池扣伤害 | 相同位置（`LivingDamageEvent.Pre` 之后由容器管理吸收池） | ≈ 一致 |
| armor damage（耐久伤害源） | 非绕过护甲的伤害均损甲；铁砧/落块对头盔有独立规则（×0.75 伤害同，耐久量不同） | 同条件；铁砧/落块 `DAMAGES_HELMET` 规则相同 | ≈ 一致（头盔特例耐久量微小差异，可选修复） |
| modded armor compatibility | 仅识别 `ItemArmor` 子类 | 属性驱动，任何 Mod 盔甲自动生效 | 实现上必须基于属性读取（读 `getArmorValue()`） |
| invulnerability frames | `hurtResistantTime > 10` 差值结算 | `invulnerableTime > 10` 差值结算 | ≈ 一致 |
| 无敌帧内 lastHurt 语义 | 记录护甲前伤害 | 同（NeoForge patch 明确修复为 pre-reduction） | ≈ 一致 |

---

## F. 实现方案分析

### 方案 1：NeoForge Event only（`LivingIncomingDamageEvent` + `DamageContainer.addReductionModifier`）

实现要点（描述性，非代码）：
1. 监听 `LivingIncomingDamageEvent`；
2. 若 `source` 在 `bypasses_armor` 标签中（等价旧 `isUnblockable`）→ 不注册任何函数，直接放行；
3. 否则注册两个减伤替换函数：
   - `Reduction.ARMOR`：被调用时 `container.getNewDamage()` 恰为**格挡后、护甲前**伤害 X；计算旧减伤 `X - X×(25-clamp(armor,0,20))/25`（armor = `getArmorValue()`；可选：对护甲比例再套 `EnchantmentHelper.modifyArmorEffectiveness` 保留 Breach 附魔功能），返回它替换原版减伤值；
   - `Reduction.ENCHANTMENTS`：被调用时伤害已是旧护甲后的 Y；用旧 EPF 公式（从各护甲件 `ItemStack` 附魔组件取等级 → 旧材质倍率表 → 总和 clamp 25 → 服务端随机 [⌈EPF/2⌉,EPF] → cap 20）计算 `Y - Y×(1-EPF/25)`，替换原版附魔减伤；
   - 抗性（`MOB_EFFECTS`）不动：两代公式相同。
4. 护甲耐久：1.8.9 与现代规则一致，默认不动；如需精确复刻铁砧头盔特例，用 `ArmorHurtEvent`。

| 评估项 | 结果 |
|---|---|
| 代码侵入程度 | 零侵入（纯官方 API） |
| 与其他 Mod 冲突风险 | 极低。事件可叠加：Incoming 中其他 Mod 的 `setAmount` 会先于护甲计算生效（护甲作用于其修改后的伤害，语义正确）；Pre 中其他 Mod 的修改叠加在我们的结果之上，不会被我们抹掉 |
| 是否完整恢复旧机制 | 是（含 EPF 随机化、bypass 过滤）。已知边界：`ENCHANTMENTS` 的替换函数只在原版 EPF>0 时被调用——原版 EPF=0 而旧 EPF>0 的组合（Mod 伤害源标了火但不在 is_fire 标签等）极罕见，可加 `LivingDamageEvent.Pre` 兜底修正 |
| 支持 Mod 添加的 armor | ✅ 读 `ARMOR` 属性，Mod 盔甲自动计入 |
| 支持 Mod 修改 armor attribute | ✅ 属性修饰符（药水、Mod、命令）全部生效 |
| 支持其他 Mod 修改 damage | ✅ 优于 Mixin：Incoming 层改动进入护甲前伤害；Pre 层改动覆盖式生效，互不破坏 |
| 多人服务器一致性 | ✅ 纯服务端；客户端无逻辑 |
| 维护成本 | 低；依赖的是 NeoForge 公共 API（DamageContainer/LivingIncomingDamageEvent） |
| 对 NeoForge 更新的敏感度 | 中低：API 合约稳定（2023 年引入后仅有小改）；若某版本改变 `setReduction` 语义需跟进，可用方案 1b（Pre 覆盖）兜底 |

**变体 1b（Pre 覆盖式）**：在 `LivingDamageEvent.Pre` 中 `setNewDamage(旧管线(原伤害 - 格挡 - 无敌帧差值))`。更简单直接，但会把其他 Mod 在 Incoming/Pre 中对伤害的修改一并重算/覆盖，兼容性不如 1；推荐仅作为兜底开关（配置项）。

### 方案 2：Mixin / injection 到现代护甲计算（`LivingEntity#getDamageAfterArmorAbsorb` / `getDamageAfterMagicAbsorb`）

| 评估项 | 结果 |
|---|---|
| 代码侵入程度 | 中（Mixin 到原版方法；需 MixinConfigPlugin、remap） |
| 冲突风险 | **中高**：任何同样 Mixin 这两个方法的 Mod（各类 OldCombat/PVP 定制 Mod 常见目标）直接冲突；NeoForge 社区对 Mixin 兼容问题排查成本高 |
| 完整恢复旧机制 | 最精确（公式、随机数、顺序全部自主控制）；且因 NeoForge 的 actuallyHurt 会把返回值记入 DamageContainer，事件数值仍正确 |
| 支持 Mod armor/属性 | ✅（同读属性） |
| 支持 Mod 修改 damage | ✅（在管线内） |
| 多人一致性 | ✅ 服务端 |
| 维护成本 | 中：每次 MC 映射名/签名变化要重新适配 |
| 对 NeoForge 更新敏感度 | 中：NeoForge patch 改写 `actuallyHurt` 时（如 1.21 的大改）可能需要重定位注入点 |

### 方案 3：修改/替换 `CombatRules` 类计算

事实：`CombatRules` **没有被 NeoForge patch**，NeoForge 也没有类替换机制；因此“替换 CombatRules”在 NeoForge 上只有 Mixin 一条路（重定向/覆写 `getDamageAfterAbsorb` + `getDamageAfterMagicAbsorb`），实质是方案 2 的一个更窄变体。
- 优点：注入面小（纯公式类，无状态），签名稳定；
- 缺点：同方案 2 的 Mixin 冲突与 remap 问题；且 `getDamageAfterAbsorb` 内部还嵌着 Breach 钩子（`EnchantmentHelper.modifyArmorEffectiveness`），覆写时要决定去留；`getDamageAfterMagicAbsorb` 是通用减伤函数（也被其他代码复用），覆写影响面需评估。

### 方案 4：取消事件 + 完全自管伤害（`LivingIncomingDamageEvent.cancel` + 手动 setHealth）

**否决**。取消事件后原版的图腾、死亡、无敌帧、击退、饱食、统计、CombatTracker 全部被跳过，需要全部手工复刻；与其他 Mod 的伤害类事件全链路失效；任何遗漏都会造成 PVP/生存体验破坏。仅适合极端 Total-Conversion 场景。

### 方案对比总表

| 评估维度 | 1. Event+ReductionModifier（推荐） | 1b. Pre 覆盖 | 2. Mixin→LivingEntity | 3. Mixin→CombatRules | 4. Cancel+自管 |
|---|---|---|---|---|---|
| 侵入度 | 无 | 无 | 中 | 中 | 无（但逻辑外包） |
| Mod 冲突风险 | 极低 | 低（覆盖语义） | 中高 | 中高 | 高 |
| 旧机制还原度 | 完整（罕见 EPF 门控边界需兜底） | 完整 | 完整 | 完整（公式层） | 取决于复刻质量 |
| Mod armor/属性/伤害修改 | 全支持 | armor 支持；Pre 修改会被覆盖 | 全支持 | 全支持 | 差 |
| 多人一致性 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 维护成本 | 低 | 低 | 中 | 中 | 高 |
| NeoForge 更新敏感度 | 中低 | 低 | 中 | 中 | 中 |

### F.4 推荐方案与遗留设计决策

**推荐：方案 1（Event + `DamageContainer.addReductionModifier`）为主体，配置项提供 1b（Pre 覆盖）作为兜底开关；不使用 Mixin。**

理由：唯一能在“不拦截其他 Mod 伤害逻辑”的前提下整体接管护甲/附魔两层减伤的官方路径；护甲值读取基于 `ARMOR` 属性，Mod 盔甲与属性修改天然兼容；无 Mixin 冲突面；EPF 随机化等服务器侧行为不受影响。

实现前需锁定的设计决策（写代码前应确认）：
1. **armor clamp**：旧公式无 clamp，但现代属性上限 30。建议 `clamp(armor, 0, 20)`（原版满钻=20，行为不变；防止 Mod 超额护甲产生 0/负伤害）。
2. **Toughness 属性**：直接忽略（旧机制核心诉求）。
3. **Breach 附魔**：建议在新公式上继续套用 `modifyArmorEffectiveness`（保持新附魔可用），可配置关闭。
4. **EPF 取值**：原版五件套用旧倍率表（protection 5/件@IV 等）+ 服务端随机化；Mod 附魔的 `damage_protection` 值按“等效 EPF”并入（随机化与否可配置）。
5. **EPF 门控兜底**：`LivingDamageEvent.Pre` 中检测“旧 EPF>0 但容器 ENCHANTMENTS 减伤=0 且现代 EPF=0”的罕见组合并修正。
6. **格挡**：保持 26.2 现代盾牌，不改。
7. **伤害类型过滤**：直接使用现代 `bypasses_armor` 标签（与旧 `isUnblockable` 对共有伤害源等价）。
8. **统计数值**：`DAMAGE_RESISTED` 等按原版中间值记录，属可接受偏差；如需精确可在 Post 中补偿（低优先级）。
9. **客户端**：无需任何客户端代码（护甲条显示逻辑基于属性，不受减伤公式影响；1.8.9 的护甲条抖动是客户端表现，可选做）。

---

## 附：关键文件索引

| 内容 | 位置 |
|---|---|
| 26.2 反编译源码 | `decomp262/net/minecraft/world/entity/LivingEntity.java`（hurtServer L1141 / applyItemBlocking L1259 / getArmorValue L1756 / hurtArmor L1760 / getDamageAfterArmorAbsorb L1779 / getDamageAfterMagicAbsorb L1787 / actuallyHurt L1825） |
| 26.2 减伤公式 | `decomp262/net/minecraft/world/damagesource/CombatRules.java` |
| 26.2 伤害标签 | `reference/minecraft-26.2-source/data/minecraft/tags/damage_type/*.json` |
| 26.2 附魔定义 | `reference/minecraft-26.2-source/data/minecraft/enchantment/{protection,fire_protection,blast_protection,projectile_protection,feather_falling,breach}.json` |
| 26.2 材质/属性 | `decomp262/net/minecraft/world/item/equipment/ArmorMaterials.java`、`.../ai/attributes/Attributes.java` |
| NeoForge patch | `neoforge262/LivingEntity.java.patch`、`Player.java.patch`、`EnchantmentHelper.java.patch` |
| NeoForge API | `neoforge262/DamageContainer.java`、`LivingIncomingDamageEvent.java`、`LivingDamageEvent.java`、`ArmorHurtEvent.java`、`LivingShieldBlockEvent.java`、`IReductionFunction.java`（ fetched ）、`CommonHooks.java` |
| 1.8.9 源码 | `reference/minecraft-1.8.9-source/src/minecraft/net/minecraft/entity/EntityLivingBase.java`（attackEntityFrom L863 / getTotalArmorValue L1192 / damageArmor L1208 / applyArmorCalculations L1215 / applyPotionDamageCalculations L1231 / damageEntity L1276）、`entity/player/EntityPlayer.java`（attackEntityFrom L1051 / damageEntity L1156）、`entity/player/InventoryPlayer.java`（L710/L729）、`item/ItemArmor.java`（材质表 L237）、`enchantment/EnchantmentProtection.java`（calcModifierDamage L73）、`enchantment/EnchantmentHelper.java`（L197）、`util/DamageSource.java`（L14-27） |

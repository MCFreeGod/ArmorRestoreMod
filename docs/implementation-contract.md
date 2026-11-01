# ArmorRestoreMod 实现合同（Implementation Contract）

> 本文是下一步正式编码的**唯一依据**。基于 NeoForge 26.2.x 分支实际源码（2026-09-08 拉取）与 1.8.9 MCP 源码的实现级验证结论。
> 前置调查见 `armor-mechanism-investigation.md`。
>
> **移植记录（2026-09-23）：已移植到 Minecraft 26.3 + NeoForge 26.3.0.12-beta。** 伤害管线与扩展点无变化：26.3.x 的 `LivingEntity.java.patch` 与 26.2.x 逐行相同（仅行号偏移），`DamageContainer` / `LivingIncomingDamageEvent` / `IReductionFunction` 逐字节相同；Mod 代码只需 2 处适配——`Entity#invulnerableTime` 由 public 字段改为 private（26.3 中无敌帧计数器更名为 `LivingEntity#damageCooldownTime`，为 public 字段，自测/命令使用它）；MDG 插件升至 2.0.147。运行时自测（含摔落回归）在 26.3 上全部 PASS。
>
> **移植记录（2026-10-04，双平台）：已新增 Fabric 支持，项目变为双工程结构。**
> - `mod/` = NeoForge 版（Minecraft 26.3 + NeoForge 26.3.0.48-beta），实现方式不变（`DamageContainer.addReductionModifier`，零 Mixin）。
> - `mod-fabric/` = Fabric 版（Minecraft 26.3 + Fabric Loader 0.19.5 + Fabric API 0.161.0+26.3），用 Mixin 实现。
> - **关键事实纠正**：Minecraft 自 26.x 起**已完全移除混淆**，Yarn 映射使命结束（不再更新），Mojang 也不再发布 `client_mappings`。因此 Fabric 侧使用 **`net.fabricmc.fabric-loom` 插件（非 `-remap` 变体）且不声明任何 mappings**，官方 `fabric-example-mod` 的 `26.3` 分支即为此配置；依赖用 `implementation` 而非 `modImplementation`。最初按 1.21.11 旧模板配置导致的 "Failed to find official mojang mappings" 是配置错误，不是上游缺失。
> - **Fabric 侧 Mixin 注入点必须是 `HEAD`，不能用 `RETURN`**：原版 `getDamageAfterArmorAbsorb` 的方法体把结果写回 `damage` 参数所在的局部变量槽（`fstore_2` 后 `freturn`），在 `RETURN` 处 Mixin 读到的 `damage` 参数已是原版减伤后的值，用作输入会导致**双重减伤**（实测 armor=10 时 10→8→4.8）。`HEAD` 注入可拿到原始伤害；因取消了原版方法体，需自行复刻其两个副作用：`bypasses_armor` 门控与 `hurtArmor` 护甲耐久调用。
> - 两端均已通过：`build` 成功、10/10 单元测试、dev server 运行时自测（armor 0/10/20 + 摔落回归）全部 PASS。
> - 配置差异：NeoForge 用 `ModConfigSpec`（`config/armorrestore-local.toml`）；Fabric 用极简 JSON（`config/armorrestore.json`），字段与默认值一致（enabled / armorClampMin / armorClampMax / breachIntegration）。
>
> **移植记录（2026-10-04）：已升级到 NeoForge 26.3.0.48-beta（26.3 系列最新）。** 伤害管线零变化：`26.3.x` 分支的 `LivingEntity.java.patch` 与 26.3.0.12 时的快照**逐字节相同**，`DamageContainer` / `LivingIncomingDamageEvent` / `IReductionFunction` 的方法签名在 26.3.0.48-beta 发布 jar 中逐项核对无变化；MDG 升至 2.0.148；neoform 仍为 `26.3-1`（MC 26.3 不变）。唯一代码适配：**FML 12（随 26.3.0.4x 引入）重命名了配置类型**——`ModConfig.Type.COMMON/SERVER` 被 `LOCAL / CLIENT / SYNCED / STARTUP` 取代，本项目由 `COMMON` 改为 **`LOCAL`**（两端加载、存全局 config 目录、不同步，语义与旧 COMMON 等价），配置文件由 `armorrestore-common.toml` 更名为 `armorrestore-local.toml`。运行时自测（含摔落回归）在 26.3.0.48-beta 上全部 PASS。
>
> **变更记录（2026-10-11）：隐藏"盔甲韧性"tooltip 行。**
> - **动机**：旧公式完全不使用 armor toughness，`+X 盔甲韧性` 描述的是不再影响伤害的属性，会误导玩家。
> - **不能用 `GatherSkippedAttributeTooltipsEvent#skipId`**：26.3 中 `ArmorMaterial#createAttributes` 把 **armor、armor_toughness、knockback_resistance 三者声明在同一个 modifier id**（`minecraft:armor.<type>`，由 `ArmorMaterial.java` 与 26.3 客户端 jar 字节码双重确认）。按 id 跳过会连"盔甲 +X"一起删掉，破坏有效信息。
> - **实现方式**：在 tooltip **生成完成之后**按翻译键过滤成品行。识别依据是行内嵌套的 `Component.translatable("attribute.name.armor_toughness")`（属性名参数），而非渲染后的文本——因此**不受客户端语言影响**，也不会因某语言译文恰好含相同字样而误删。
>   - NeoForge：`ItemTooltipEvent`（`getTooltipLines` 内、全部行生成后触发）。
>   - Fabric：`ItemTooltipCallback`（`fabric-item-api-v1`，由 client mixin 在 `getTooltipLines` RETURN 处触发）——**client-only API**，故 Fabric 侧新增独立 `client` 入口点 `com.mcfreegod.armorrestore.client.ArmorRestoreClient`，避免专用服务器加载客户端类。
> - **共享代码**：`com.mcfreegod.armorrestore.tooltip.ToughnessTooltipFilter`（零平台依赖）在两端各存一份源码，与 `OldArmorReduction` 同一策略。
> - **配置**：新增 `hideArmorToughnessTooltip`（NeoForge）/ `hideArmorToughness`（Fabric），默认 `true`，受总开关 `enabled` 门控。
> - **验证**：两端单测 17/17（原 10 项 + 新增 7 项 tooltip 过滤测试）；两端运行时自测新增钻石胸甲断言——**并带前置断言**（先确认胸甲确实声明了 toughness，否则"没有韧性行"会空过），结果 `declares toughness=true | armor line present=true | toughness line present=false -> PASS`。NeoForge 侧另以**实际部署的 jar** 在独立游戏目录、独立 classpath 下完成生产环境端到端验证。
> - **NeoForge 单测 classpath**：MDG 默认不把 MC 放进测试 classpath，其官方 `neoForge.unitTest { enable() }` 会引入 `net.neoforged.fancymodloader:junit-fml`，该构件**不在任何可达仓库**（镜像 404、官方源直连被重置）。改用 `sourceSets.test.compileClasspath/runtimeClasspath += sourceSets.main.*` 复用主 classpath 即可满足需要（测试仅构造 `Component`；`TranslatableContents` 的开发期参数校验在 FML 缺席时自动跳过，见其源码注释）。
> - **产物命名**：两端 jar 加平台前缀以区分——`[NeoForge] armorrestore-0.1.0-26.3.jar` / `[Fabric] armorrestore-0.1.0-26.3.jar`（经 `base.archivesName`）。内部 mod 版本仍为 `0.1.0`。
>
> **变更记录（2026-11-01）：包名迁移 + mod 图标。**
> - **包名**：`com.armorrestore` → **`com.mcfreegod.armorrestore`**（两端一致，`mod_group_id` 同步更新）。涉及全部 `package`/`import` 声明、Fabric `fabric.mod.json` 的 `entrypoints`、`armorrestore.mixins.json` 的 `package`。**mod id 保持 `armorrestore` 不变**——它是存档与配置的持久化标识（`config/armorrestore.json`、`armorrestore-local.toml`、ModList 条目），改它会让玩家已有配置失联，与包名无关。
> - **图标**：256×256 PNG，**钻石胸甲**，原版像素风（`icon.png`，放各工程 `src/main/resources/`）。
>   - **为什么不是盾牌**：本 Mod 的立意是"恢复旧版强力护甲、从而减少对盾牌的依赖"，盾牌图标与主旨相矛盾。第一版误用盾牌，已废弃。
>   - **像素来源**：胸甲的像素布局是**原版贴图 `assets/minecraft/textures/item/diamond_chestplate.png` 的逐像素照搬**（16×16，含原版调色板 `#082520 / #0E3F36 / #20C5B5 / #1AAAA7 / #4AEDD9 / #A1FBE8 / #FFFFFF`），因此图标读起来像游戏本体的一部分，而不是通用 UI 美术。
>   - **渲染方式**：在 20×20 网格上以 **1:1 像素比例**贴入 16×16 精灵（留 2 格边距），再以 NEAREST 放大到 256px——全程无缩放、无抗锯齿混合，像素边缘保持锐利；32px 下轮廓仍可辨（已验证）。
>   - 生成脚本见 [`tools/make_icon.py`](../tools/make_icon.py)（`python tools/make_icon.py`，需要 Pillow）。
> - **图标声明方式**：NeoForge 侧用 **`iconFile="icon.png"`**，不能用 `logoFile`——后者在 26.x 已弃用，`LogoFileWarningsHandler` 会在开发环境弹出警告（源码注释明确要求改为 `bannerFile`/`iconFile`，方形图标用 `iconFile`）。Fabric 侧用 `fabric.mod.json` 的 `"icon": "icon.png"`。
> - **验证**：两端单测各 17/17；两端运行时自测全部 PASS（改包后 Mixin 与事件钩子均正常）；NeoForge 侧以新构建的 jar 在独立游戏目录完成生产环境端到端验证（`armorrestore (jar(mods/[NeoForge] armorrestore-0.1.0-26.3.jar))` 加载成功，全部断言 PASS）。

---

## 一、DamageContainer / ReductionModifier 实现级验证

### 1.1 注册时机（addReductionModifier 何时调用才有效）

**调用链（全部来自已拉取源码，非猜测）**：

```
LivingEntity#hurtServer（patch @@ -1188）
  └─ this.damageContainers.push(new DamageContainer(source, damage));
  └─ CommonHooks.onEntityIncomingDamage(this, container)          // CommonHooks.java L326
       └─ NeoForge.EVENT_BUS.post(new LivingIncomingDamageEvent(entity, container))
            └─ 【我们的监听器】container.addModifier(Reduction.ARMOR, fn)
                 └─ DamageContainer#addModifier（L116）:
                    reductionFunctions.computeIfAbsent(type, a -> new ArrayList<>()).add(fn)
                    // EnumMap<Reduction, List<IReductionFunction>>，按插入顺序排列
```

精确表述：**`addModifier` 只要求在对应 `setReduction` 发生之前被调用**。`Reduction.ARMOR / MOB_EFFECTS / ENCHANTMENTS` 三者的 `setReduction` 全部发生在 `LivingDamageEvent.Pre` 触发之前，因此 **Incoming 是注册这三类 modifier 的唯一事件时机**（Pre 之后注册对它们无效；Pre 之后只有 `ABSORPTION` 的 `setReduction` 还未发生）。容器在 `hurtServer` 出口 pop 并废弃，注册不跨伤害持久。

### 1.2 执行时机（modifier 何时运行）

在 `actuallyHurt`（patch @@ -1941）中：

```java
this.damageContainers.peek().setReduction(Reduction.ARMOR,
        this.damageContainers.peek().getNewDamage()
        - this.getDamageAfterArmorAbsorb(source, this.damageContainers.peek().getNewDamage()));
```

`DamageContainer#setReduction`（L184）：

```java
public void setReduction(Reduction reduction, float amount) {
    float modifiedReduction = modifyReduction(reduction, amount);   // ← modifier 链在此执行
    this.reductions.put(reduction, modifiedReduction);
    this.newDamage = Math.max(this.newDamage - modifiedReduction, 0); // ← 扣减在链执行完之后
}
private float modifyReduction(Reduction type, float reduction) {
    for (var func : reductionFunctions.getOrDefault(type, List.of())) {
        reduction = func.modify(this, reduction);                   // 链式传递
    }
    return reduction;
}
```

即：modifier 在**原版护甲计算刚完成、容器尚未扣减该减伤**的瞬间执行。

### 1.3 vanillaReduction 参数的含义

`setReduction` 的第二参（`IReductionFunction.modify` 的 `reductionIn`）= **本次减伤类型刚被原版计算出的减伤量，绝对伤害单位（不是比例）**：

- ARMOR：`preArmorDamage − getDamageAfterArmorAbsorb(...)`，即现代公式的护甲减伤值；
- ENCHANTMENTS：`postArmorNewDamage − CombatRules.getDamageAfterMagicAbsorb(...)`，即现代 EPF 减伤值；
- modifier 执行时 `container.getNewDamage()` **尚未被该减伤扣减**（扣减在 `modifyReduction` 返回后才发生），所以 ARMOR modifier 运行时读到的 `getNewDamage()` 就是"格挡后、护甲前"伤害 X。

### 1.4 返回值语义：**链式替换，不是相加**

每个函数接收"当前累计减伤值"（第一个函数收到 vanilla 值，后续函数收到前一个函数的返回值），返回值**整体替换**当前减伤值。容器最终 `reductions[类型] = 最后一个函数的返回值`，`newDamage = max(newDamage − 该值, 0)`。

### 1.5 数字示例（Original=20，Vanilla Armor Reduction=12，Old Armor Reduction=8）

1. `Incoming` 事件：容器建立，`newDamage=20`；我们注册 ARMOR modifier。
2. `actuallyHurt`：原版 `getDamageAfterArmorAbsorb(20)` 返回 8（现代公式扣了 12）；调用 `setReduction(ARMOR, 12)`。
3. `modifyReduction` 执行我们的函数：入参 `reductionIn=12`（我们**不使用它**），此时 `container.getNewDamage()` 仍为 20（未扣减）；我们计算 `oldReduction = 20 − 20×(1 − clamp(armor,0,20)×0.04) = 8`，返回 8。
4. `reductions[ARMOR]=8`，`newDamage = max(20−8,0) = 12`。
5. 后续：附魔/抗性/吸收/扣血全部基于 12 进行；`LivingDamageEvent.Post` 中 `getReduction(ARMOR)=8`（数值正确，可供其他 Mod 消费）。

结论：最终伤害与容器记录均为旧公式结果，无需任何 Mixin。

---

## 二、多 Mod 兼容性验证

### 2.1 三个 ARMOR modifier（Mod A、Mod B、本 Mod）

- **执行顺序** = 函数插入顺序 = `LivingIncomingDamageEvent` 监听器调用顺序。NeoForge 事件总线（`EventPriority.java` 官方源码）按 **HIGHEST → LOWEST** 排序，同优先级按**注册顺序**（mod 总线 = mod 加载顺序）。
- **替换还是叠加**：链式替换。vanilla 12 → A(12)→a → B(a)→b → 本Mod(b)→8。每个函数都有权完全覆盖前面的结果。
- **是否可能互相覆盖**：是。**最后执行的 modifier 拥有最终决定权**；这是该 API 的既定语义（javadoc："These are always executed in insertion order. if sequence matters, use EventPriority"）。
- **排序机制**：仅事件监听器优先级 + 注册顺序；reduction function 本身无独立排序 API。

### 2.2 本 Mod 的注册策略

- 监听器优先级 **HIGH**（早于默认 NORMAL 的 Mod）：让本 Mod 的旧公式值**先**成为基线，其他 Mod 的 ARMOR modifier（通常 NORMAL/LOW）在我们之后执行、仍可继续调整——这是对他 Mod 破坏最小的位置（排在最后会无条件覆盖他 Mod 的修改）。
- 本 Mod 的函数**不读 `reductionIn`、只从 `container.getNewDamage()` 与实体属性确定性计算**：无论链条顺序如何，我们的输出恒为旧公式值，行为可预测。
- 绝不取消 `LivingIncomingDamageEvent`；不使用 `setAmount`。

### 2.3 与其他 Mod 修改行为的叠加结果

| 他 Mod 行为 | 与本 Mod 共存时的实际结果 |
|---|---|
| `LivingIncomingDamageEvent.setAmount()` | 其修改后的伤害成为"护甲前伤害 X"，原版护甲与本 Mod 旧公式**都作用于 X** → 他 Mod 的修改被旧护甲正常"披甲"，语义一致，无冲突 |
| `LivingDamageEvent.Pre.setNewDamage()` | 发生在我们所有减伤之后：他的绝对值直接决定最终扣血（其意图覆盖我们的结果）→ 符合覆盖语义，无结构性冲突 |
| 他 Mod 取消 Incoming | 整次伤害作废，我们的 modifier 随容器废弃，无残留 |

---

## 三、Armor 行为锁定

**公式（唯一）**：`damageAfterArmor = damage × (1 − clamp(armor, 0, 20) × 0.04)`

- 0 Armor=0%、5=20%、10=40%、15=60%、20=80%。
- **Armor Toughness 完全不参与**：我们的函数永不读取 `ARMOR_TOUGHNESS`。
- **armor 来源**：`LivingEntity#getArmorValue()`（= `floor(getAttributeValue(ARMOR))`）。取整（floor）忠实于 1.8.9 的整数模型（1.8.9 护甲值恒为整数和）。
- **Modded armor / Attribute modifiers 自动支持**：属性修饰符（Mod 盔甲、药水、命令）全部经由 `ARMOR` 属性汇入，无需额外处理。
- **Bypass**：伤害类型在 `bypasses_armor` 标签时，原版不会调用 `setReduction(ARMOR)`，我们的函数自然不执行 → bypass 行为自动保持，无需重复判断。
- **Shield / Absorption / Resistance（Mob Effect）**：全部保持 26.2 原样（其中 Resistance 公式与 1.8.9 数值相同）。
- **Armor durability**：不修改。现代 `doHurtEquipment` 的耐久量（每件 `max(1, 护甲前伤害/4)`，四件同额）与 1.8.9 `InventoryPlayer#damageArmor` 完全一致，已验证。

### "armor > 20" 的处理（专项分析）

**A. 严格复刻 1.8.9**：公式无 clamp。但 1.8.9 原版可达护甲上限就是 20（3+8+6+3），"无 clamp"在原版下**不可观测**；只有在 Mod 加大护甲时才暴露：armor 21–24 → 减伤 84%–96%+；armor ≥25 → 0 伤害；armor=30（现代属性上限）→ 减伤 −20%（**伤害反而增大 20%**）。

**B. 现代 Mod 兼容**：`clamp(armor, 0, 20)`。对 0–20 的所有值与 A 完全相同；对 Mod 超额护甲给出有界合理行为（最高 80%）。另注意 1.8.9 里 armor≥25 的"负减伤→伤害放大"在任何时代都是反直觉缺陷，无复刻价值。

**最终推荐（已锁定）**：**采用 clamp(armor, 0, 20)**（即本文开头的公式）。理由：对 1.8.9 原版可达值域与严格复刻 100% 等价；对现代 Mod 护甲给出安全上界；避免负减伤。此决策记录为本项目行为规范。

---

## 四、Breach 行为决策

| 方案 | 内容 | 优点 | 缺点 |
|---|---|---|---|
| A | 完全取消 Breach（不调用 `modifyArmorEffectiveness`） | 实现最简；"1.9 前无此附魔"字面成立 | Breach 在旧公式世界变成死附魔（旧公式本无 toughness 可穿透，再无任何作用）；删除现代原版内容，偏离"兼容现代"目标 |
| B | **保留 Breach，作用于旧 Armor reduction**：`f = clamp(armor,0,20)/25` → `f' = EnchantmentHelper.modifyArmorEffectiveness(level, weapon, victim, source, f)` → `damage × (1 − f')` | 现代 enchantment 保持有意义；复用官方公共 API（服务端公共静态方法，已验证存在）；旧公式整体结构不变 | 非严格 1.8.9（但 1.8.9 本无 Breach，任何方案都需人为定义其行为；B 是最小集成定义） |
| C | 其他（按伤害源映射、配置枚举等） | — | 过度设计 |

**最终决策（已锁定）**：**默认采用 B**（Breach 继续生效，作用于旧 armor fraction），提供配置项切换为 A。此为本项目行为设计决策的正式记录，不视为新增游戏内容。

---

## 五、ENCHANTMENTS 层独立替换可行性（技术确认，Protection 暂不实现）

1. **`Reduction.ENCHANTMENTS` 何时写入**：在 Neo 版 `getDamageAfterMagicAbsorb`（patch @@ -1934）：
   ```java
   if (enchantmentArmor > 0.0F) {     // enchantmentArmor = EnchantmentHelper.getDamageProtection(...)
       damage = CombatRules.getDamageAfterMagicAbsorb(damage, enchantmentArmor);
       this.damageContainers.peek().setReduction(Reduction.ENCHANTMENTS,
               this.damageContainers.peek().getNewDamage() - damage);
   }
   ```
   执行顺序位于 ARMOR 与 MOB_EFFECTS（抗性）之后、`LivingDamageEvent.Pre` 之前；modifier 运行时 `container.getNewDamage()` = "旧护甲后、抗性后"伤害 Y。

2. **原版 EPF = 0 时我们的 ENCHANTMENTS modifier 会不会执行**：**不会。** `setReduction(ENCHANTMENTS, ...)` 被 `if (enchantmentArmor > 0.0F)` 门控，EPF=0 时该行根本不执行，已注册的 modifier 永远不会被调用。`DamageContainer#addModifier` 的 javadoc 明确记录了该行为（"if the vanilla reduction is not triggered, the reduction function will not execute"）。

3. **"现代 EPF=0 但旧版 EPF>0" 的最干净处理**：
   - 范围分析：按本项目政策（旧 EPF 触发条件映射到现代伤害标签 is_fire/is_fall/is_explosion/is_projectile，protection 无条件；Mod 附魔按其现代 `damage_protection` 值计入 EPF），**所有原版内容下"现代 EPF=0 ⇔ 旧 EPF=0"**。发散只可能来自 Mod 伤害源（如设置了火焰标志但不在 is_fire 标签）或极端的 Mod 附魔条件。
   - 处理方式：在 `LivingDamageEvent.Pre` 做窄修正——若 `旧EPF>0 && container.getReduction(ENCHANTMENTS)==0` 且伤害源未绕过等效条件，则 `newDamage −= newDamage × (clamp(EPF,0,20)/25)`。该修正的数学位置正确（Pre 时的 newDamage 已含抗性；旧顺序=抗性→EPF ✓）。
   - **结论：`LivingDamageEvent.Pre` 兜底对原版行为不是必需的**（原版下该情况不存在）；作为 Mod 边界情况的窄安全网保留（约十行，配置可关，阶段二实现）。不采用"在 Pre 重算整条管线"的重方案。

---

## 六、实现合同

### 【必须实现】

*（阶段一：Armor）*
- 旧版 Armor damage reduction：`damage × (1 − clamp(armor,0,20) × 0.04)`，经 `Reduction.ARMOR` 替换式 modifier 实现；armor 取 `getArmorValue()`；toughness 不参与。
- 配置项：Mod 总开关、armor clamp 上下界（默认 0/20）、Breach 集成开关（默认开）。

*（阶段二：Protection）*
- 旧版 Protection 机制（EPF）：经 `Reduction.ENCHANTMENTS` 替换式 modifier 实现；遍历 4 个护甲槽的附魔组件；原版五类保护用旧倍率表（protection ×0.75 / fire ×1.25 / fall ×2.5 / blast ×1.5 / projectile ×1.5，f=(6+lv²)/3），Mod 附魔按现代 `damage_protection` 值计入；条件判断用现代伤害标签；总和 clamp [0,25] → 服务端随机 [⌈EPF/2⌉, EPF] → 实体 cap 20 → `damage × (25−EPF)/25`。
- `LivingDamageEvent.Pre` 窄兜底（EPF 门控边界，配置可关）。

### 【保持现代】

- Shield（`BLOCKS_ATTACKS` 组件、`LivingShieldBlockEvent`）——完全不动。
- Resistance / 全部 Mob Effect——不动（公式数值与旧版一致）。
- Absorption——不动（容器在 Pre 之后管理吸收池）。
- Armor durability——不动（数量规则已与 1.8.9 一致；`ArmorHurtEvent` v1 不使用）。
- 无敌帧、击退、图腾、死亡、统计、饱食消耗——不动。
- 伤害类型标签系统（`bypasses_armor` 等作为过滤依据）——沿用。
- Armor 属性系统（属性上限、修饰符管线）——不动。
- Breach → 按第四节决策作用于旧 armor fraction（可配置关闭）。

### 【暂时不实现】

- 剑格挡 / 任何格挡行为改动。
- 1.8.9 铁砧头盔耐久特例的现代数值修正（差异仅耐久量，影响极小，可选后续）。
- 非护甲槽装备的 EPF（1.8.9 生物主手场景；现代保护附魔均限定 armor 槽）。
- 客户端护甲条抖动等表现层行为。
- 攻击冷却、横扫等其余战斗机制（明确超出本项目范围）。

### 【最终 Hook】

- `LivingIncomingDamageEvent`（监听器优先级 HIGH，NORMAL 亦可，取 HIGH 以为他 Mod 留出后置调整空间）：
  - `container.addReductionModifier(Reduction.ARMOR, fnArmor)`（阶段一）
  - `container.addReductionModifier(Reduction.ENCHANTMENTS, fnEPF)`（阶段二）
- `LivingDamageEvent.Pre`（NORMAL，仅阶段二，窄兜底）。
- 不注册其他任何伤害类事件。

### 【是否使用 Mixin】

**否。** 全程零 Mixin、零 ASM、零访问越权（所需 API 均为 NeoForge 公共 API 与原版公共方法）。

### 【最终推荐实现方案】

> **"NeoForge Event-Only：通过 `DamageContainer.addReductionModifier` 以链式替换语义接管 `Reduction.ARMOR`（旧护甲公式）与 `Reduction.ENCHANTMENTS`（旧 EPF 公式），`LivingDamageEvent.Pre` 作 EPF 门控窄兜底；Shield/Resistance/Absorption/耐久/无敌帧保持 26.2 原样；无 Mixin。"**

---

## 附：本次验证新增确认的事实清单

> **阶段一勘误（2026-09-09，摔落伤害 Bug 修复后追加）**：本文第二部分 6.3 与第五部分中"bypasses_armor 时原版不会调用 `setReduction(ARMOR)`，modifier 自然不执行"的表述**不成立**。实测（patch L478）：NeoForge 重写的 `actuallyHurt` 中 `setReduction(Reduction.ARMOR, ...)` 是**无条件调用**的——`bypasses_armor` 的门控位于原版 `getDamageAfterArmorAbsorb` **内部**，bypass 伤害时容器记录的 ARMOR 减伤为 0，但 modifier 链照常执行。因此本 Mod 的 ARMOR modifier 内部必须自行执行同一条门控：`container.getSource().is(DamageTypeTags.BYPASSES_ARMOR)` 为真时原样透传 `vanillaReduction`（已在 `OldArmorReductionHandler` 实现，并有运行时摔落回归测试覆盖）。同页对 ENCHANTMENTS 层的门控描述（`EPF>0` 才写入）仍然正确。

1. `DamageContainer#setReduction` 先跑 modifier 链、后扣减 `newDamage` → modifier 内读 `getNewDamage()` 得到的是该层减伤前的伤害值（DamageContainer.java L184-215）。
2. modifier 语义为**链式替换**（每函数返回值整体替换当前减伤），非叠加（同文件 L210-215 + `IReductionFunction` javadoc）。
3. `setReduction(ENCHANTMENTS)` 被 `if (enchantmentArmor > 0.0F)` 门控 → EPF=0 时 modifier 不执行（LivingEntity.java.patch @@ -1934）。
4. `Reduction.ARMOR/MOB_EFFECTS/ENCHANTMENTS` 的写入均早于 `LivingDamageEvent.Pre` → Incoming 是注册这三类 modifier 的唯一时机（LivingEntity.java.patch @@ -1941、@@ -1909）。
5. NeoForge 事件总线优先级：HIGHEST 最先执行，同优先级按注册顺序（mod 总线 = mod 加载顺序）（neoforged/Bus `EventPriority.java` + 本地文档站 events.html）。
6. 1.8.9 `getInventory()`：玩家 = 4 护甲槽（EntityPlayer L2294），生物 = 5 装备槽（EntityLiving L797）→ 旧 EPF 迭代范围以护甲槽为准。
7. 1.8.9 公式在 armor≥25 时会产生 0/负减伤，但原版可达上限为 20 → "无 clamp"不可观测 → clamp(0,20) 与严格复刻等价。

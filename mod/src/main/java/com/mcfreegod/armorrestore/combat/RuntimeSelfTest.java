package com.mcfreegod.armorrestore.combat;

import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import com.mcfreegod.armorrestore.tooltip.ToughnessTooltipFilter;
import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Opt-in runtime verification: starts a dedicated server with
 * {@code -Darmorrestore.selftest=true} and, once the world is up, runs the real
 * damage pipeline against a freshly spawned husk for armor 0 / 10 / 20, compares
 * the actual health loss with the restored old formula (and the modern formula,
 * for contrast), logs a PASS/FAIL report and halts the server so the run exits.
 *
 * <p>Husk is used because it does not burn in daylight, has no innate armor,
 * toughness or absorption, and NoAI keeps it perfectly still.</p>
 */
public final class RuntimeSelfTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float TEST_DAMAGE = 10.0f;
    private static final int[] TEST_ARMOR = {0, 10, 20};

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("armorrestore.selftest")) {
            return;
        }
        MinecraftServer server = event.getServer();
        boolean pass = runSelfTest(server);
        LOGGER.info("[ArmorRestore] Runtime self test {}", pass ? "PASSED" : "FAILED");
        server.halt(false);
    }

    private boolean runSelfTest(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos spawnPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, level.getRespawnData().pos());

        Husk husk = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
        if (husk == null) {
            LOGGER.error("[ArmorRestore] Could not spawn test husk");
            return false;
        }
        husk.setNoAi(true);
        husk.setPersistenceRequired();
        husk.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        level.addFreshEntity(husk);

        AttributeInstance armorAttribute = husk.getAttribute(Attributes.ARMOR);
        if (armorAttribute == null) {
            LOGGER.error("[ArmorRestore] Test husk has no armor attribute");
            husk.discard();
            return false;
        }

        int clampMin = ArmorRestoreConfig.ARMOR_CLAMP_MIN.get();
        int clampMax = ArmorRestoreConfig.ARMOR_CLAMP_MAX.get();
        DamageSource cactus = level.damageSources().cactus();
        boolean allPass = true;

        try {
            for (int armor : TEST_ARMOR) {
                armorAttribute.setBaseValue(armor);
                float healthBefore = husk.getHealth();
                husk.damageCooldownTime = 0;
                husk.hurtServer(level, cactus, TEST_DAMAGE);
                float actualLoss = healthBefore - husk.getHealth();
                husk.setHealth(husk.getMaxHealth());

                // What remains after the restored old formula is what the health SHOULD lose.
                float expectedLoss = OldArmorReduction.damageAfterArmor(
                        TEST_DAMAGE, OldArmorReduction.armorFraction(armor, clampMin, clampMax));
                float modernLoss = TEST_DAMAGE - CombatRules.getDamageAfterAbsorb(
                        husk, TEST_DAMAGE, cactus, armor,
                        (float) husk.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
                boolean match = Math.abs(expectedLoss - actualLoss) < 0.01f;
                allPass &= match;
                LOGGER.info("[ArmorRestore] armor={} damage={} | expected(old)={} | modern formula would be={} | actual health loss={} -> {}",
                        armor, TEST_DAMAGE, expectedLoss, modernLoss, actualLoss, match ? "PASS" : "FAIL");
            }

            // Regression: bypasses_armor damage (fall) must NEVER be reduced by plain
            // armor, no matter how much armor the target has. NeoForge records an ARMOR
            // reduction of 0 for these types but still runs the modifier chain.
            armorAttribute.setBaseValue(20);
            float healthBeforeFall = husk.getHealth();
            husk.damageCooldownTime = 0;
            husk.hurtServer(level, level.damageSources().fall(), TEST_DAMAGE);
            float fallLoss = healthBeforeFall - husk.getHealth();
            boolean fallPass = Math.abs(fallLoss - TEST_DAMAGE) < 0.01f;
            allPass &= fallPass;
            LOGGER.info("[ArmorRestore] armor=20 (fall, bypasses_armor) damage={} | expected(old)={} | actual health loss={} -> {}",
                    TEST_DAMAGE, TEST_DAMAGE, fallLoss, fallPass ? "PASS" : "FAIL");
        } finally {
            armorAttribute.setBaseValue(0);
            husk.discard();
        }
        allPass &= checkTooltip(level);
        return allPass;
    }

    /**
     * Verifies the tooltip cleanup against a real vanilla armor piece: the armor toughness
     * line must be gone while the armor line stays. Both are recognized by their translation
     * keys inside the component tree, so this does not depend on the client language.
     *
     * <p>The precondition assertion matters: without it a chestplate that never had a
     * toughness line would satisfy "no toughness line" and pass vacuously.</p>
     */
    private boolean checkTooltip(ServerLevel level) {
        ItemStack chestplate = new ItemStack(Items.DIAMOND_CHESTPLATE);
        boolean declaresToughness = declaresModifier(chestplate, Attributes.ARMOR_TOUGHNESS);
        if (!declaresToughness) {
            LOGGER.error("[ArmorRestore] Test precondition failed: diamond chestplate declares no armor toughness");
            return false;
        }

        List<Component> lines = chestplate.getTooltipLines(Item.TooltipContext.of(level), null, TooltipFlag.NORMAL);
        String armorKey = Attributes.ARMOR.value().getDescriptionId();
        String toughnessKey = Attributes.ARMOR_TOUGHNESS.value().getDescriptionId();

        boolean hasArmor = false;
        boolean hasToughness = false;
        for (Component line : lines) {
            hasArmor |= ToughnessTooltipFilter.containsTranslation(line, armorKey);
            hasToughness |= ToughnessTooltipFilter.containsTranslation(line, toughnessKey);
        }
        boolean pass = hasArmor && !hasToughness;
        LOGGER.info("[ArmorRestore] diamond chestplate tooltip | declares toughness={} | armor line present={} | toughness line present={} -> {}",
                declaresToughness, hasArmor, hasToughness, pass ? "PASS" : "FAIL");
        return pass;
    }

    private static boolean declaresModifier(ItemStack stack, Holder<Attribute> attribute) {
        boolean[] found = {false};
        stack.forEachModifier(EquipmentSlotGroup.CHEST,
                (attr, modifier, display) -> found[0] |= attr.is(attribute));
        return found[0];
    }
}

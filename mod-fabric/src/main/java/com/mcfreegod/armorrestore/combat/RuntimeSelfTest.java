package com.mcfreegod.armorrestore.combat;

import com.mcfreegod.armorrestore.ArmorRestoreMod;
import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import com.mcfreegod.armorrestore.tooltip.ToughnessTooltipFilter;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Opt-in runtime verification: starts a dedicated server with
 * {@code -Darmorrestore.selftest=true} and, once the world is up, runs the real damage
 * pipeline against a freshly spawned husk for armor 0 / 10 / 20, compares the actual
 * health loss with the restored old formula (and the modern formula, for contrast),
 * logs a PASS/FAIL report and halts the server so the run exits.
 *
 * <p>Husk is used because it does not burn in daylight, has no innate armor, toughness or
 * absorption, and NoAI keeps it perfectly still.</p>
 */
public final class RuntimeSelfTest {
    private static final float TEST_DAMAGE = 10.0f;
    private static final int[] TEST_ARMOR = {0, 10, 20};

    private RuntimeSelfTest() {
    }

    public static void onServerStarted(MinecraftServer server) {
        if (!Boolean.getBoolean("armorrestore.selftest")) {
            return;
        }
        boolean pass = runSelfTest(server);
        ArmorRestoreMod.LOGGER.info("[ArmorRestore] Runtime self test {}", pass ? "PASSED" : "FAILED");
        server.halt(false);
    }

    private static boolean runSelfTest(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos spawnPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, level.getRespawnData().pos());

        Husk husk = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
        if (husk == null) {
            ArmorRestoreMod.LOGGER.error("[ArmorRestore] Could not spawn test husk");
            return false;
        }
        husk.setNoAi(true);
        husk.setPersistenceRequired();
        husk.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        level.addFreshEntity(husk);

        AttributeInstance armorAttribute = husk.getAttribute(Attributes.ARMOR);
        if (armorAttribute == null) {
            ArmorRestoreMod.LOGGER.error("[ArmorRestore] Test husk has no armor attribute");
            husk.discard();
            return false;
        }

        ArmorRestoreConfig config = ArmorRestoreConfig.get();
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
                        TEST_DAMAGE, OldArmorReduction.armorFraction(armor, config.armorClampMin, config.armorClampMax));
                float modernLoss = TEST_DAMAGE - CombatRules.getDamageAfterAbsorb(
                        husk, TEST_DAMAGE, cactus, armor,
                        (float) husk.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
                boolean match = Math.abs(expectedLoss - actualLoss) < 0.01f;
                allPass &= match;
                ArmorRestoreMod.LOGGER.info("[ArmorRestore] armor={} damage={} | expected(old)={} | modern formula would be={} | actual health loss={} -> {}",
                        armor, TEST_DAMAGE, expectedLoss, modernLoss, actualLoss, match ? "PASS" : "FAIL");
            }

            // Regression: bypasses_armor damage (fall) must NEVER be reduced by plain
            // armor, no matter how much armor the target has.
            armorAttribute.setBaseValue(20);
            float healthBeforeFall = husk.getHealth();
            husk.damageCooldownTime = 0;
            husk.hurtServer(level, level.damageSources().fall(), TEST_DAMAGE);
            float fallLoss = healthBeforeFall - husk.getHealth();
            boolean fallPass = Math.abs(fallLoss - TEST_DAMAGE) < 0.01f;
            allPass &= fallPass;
            ArmorRestoreMod.LOGGER.info("[ArmorRestore] armor=20 (fall, bypasses_armor) damage={} | expected(old)={} | actual health loss={} -> {}",
                    TEST_DAMAGE, TEST_DAMAGE, fallLoss, fallPass ? "PASS" : "FAIL");
        } finally {
            armorAttribute.setBaseValue(0);
            husk.discard();
        }
        allPass &= checkTooltip(level);
        return allPass;
    }

    /**
     * Verifies the tooltip cleanup against a real vanilla armor piece. The callback that
     * applies the filter in production is client-only, so a dedicated-server run cannot
     * exercise it end to end; this drives the shared filter directly instead. The line is
     * located by translation key, so the result does not depend on the client language.
     */
    private static boolean checkTooltip(ServerLevel level) {
        List<Component> lines = new ItemStack(Items.DIAMOND_CHESTPLATE)
                .getTooltipLines(Item.TooltipContext.of(level), null, TooltipFlag.NORMAL);
        String armorKey = Attributes.ARMOR.value().getDescriptionId();
        String toughnessKey = Attributes.ARMOR_TOUGHNESS.value().getDescriptionId();

        boolean hadToughness = contains(lines, toughnessKey);
        ToughnessTooltipFilter.removeToughnessLines(lines, toughnessKey);
        boolean hasArmor = contains(lines, armorKey);
        boolean hasToughness = contains(lines, toughnessKey);

        boolean pass = hadToughness && hasArmor && !hasToughness;
        ArmorRestoreMod.LOGGER.info("[ArmorRestore] diamond chestplate tooltip | toughness line before={} | armor line kept={} | toughness line after={} -> {}",
                hadToughness, hasArmor, hasToughness, pass ? "PASS" : "FAIL");
        return pass;
    }

    private static boolean contains(List<Component> lines, String key) {
        for (Component line : lines) {
            if (ToughnessTooltipFilter.containsTranslation(line, key)) {
                return true;
            }
        }
        return false;
    }
}

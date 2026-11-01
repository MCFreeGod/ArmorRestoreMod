package com.mcfreegod.armorrestore.combat;

import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Dev-only verification command: {@code /armorrestore check <damage> <armor> [target]}.
 *
 * <p>Temporarily overrides the target's ARMOR attribute base value with the given number,
 * deals {@code <damage>} through the real server damage pipeline ({@code hurtServer} with a
 * {@code cactus} source, which does NOT bypass armor), and compares the actual health loss
 * against both the restored old formula and the modern vanilla formula. Healing the target
 * back afterwards makes the test repeatable on the same entity.</p>
 */
public final class DamageCheckCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("armorrestore")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("check")
                        .then(Commands.argument("damage", FloatArgumentType.floatArg(0.0f))
                                .then(Commands.argument("armor", IntegerArgumentType.integer(0))
                                        .executes(ctx -> runCheck(ctx, ctx.getSource().getEntity()))
                                        .then(Commands.argument("target", EntityArgument.entity())
                                                .executes(ctx -> runCheck(ctx, EntityArgument.getEntity(ctx, "target"))))))));
    }

    private static int runCheck(CommandContext<CommandSourceStack> ctx, Entity targetEntity) {
        CommandSourceStack source = ctx.getSource();
        if (!(targetEntity instanceof LivingEntity target) || !(target.level() instanceof ServerLevel level)) {
            source.sendFailure(Component.literal(
                    "[ArmorRestore] Run this as a player, or pass a living target: /armorrestore check <damage> <armor> <target>"));
            return 0;
        }
        if (target instanceof ServerPlayer player && player.getAbilities().invulnerable) {
            source.sendFailure(Component.literal(
                    "[ArmorRestore] Target player is invulnerable (creative). Switch to survival first."));
            return 0;
        }

        float damage = FloatArgumentType.getFloat(ctx, "damage");
        int armor = IntegerArgumentType.getInteger(ctx, "armor");
        ArmorRestoreConfig config = ArmorRestoreConfig.get();

        AttributeInstance armorAttribute = target.getAttribute(Attributes.ARMOR);
        if (armorAttribute == null) {
            source.sendFailure(Component.literal("[ArmorRestore] Target has no armor attribute."));
            return 0;
        }

        double savedBase = armorAttribute.getBaseValue();
        armorAttribute.setBaseValue(armor);
        try {
            DamageSource cactus = level.damageSources().cactus();
            float healthBefore = target.getHealth();
            target.damageCooldownTime = 0;
            target.hurtServer(level, cactus, damage);
            float actualLoss = healthBefore - target.getHealth();
            target.setHealth(healthBefore); // restore so the test is repeatable

            // The restored old formula: what REMAINS is what the health should lose.
            float expectedLoss = OldArmorReduction.damageAfterArmor(
                    damage, OldArmorReduction.armorFraction(armor, config.armorClampMin, config.armorClampMax));
            // What the MODERN formula would have removed, for contrast. The cactus source
            // carries no weapon, so this call is pure math with no side effects.
            float modernLoss = damage - CombatRules.getDamageAfterAbsorb(
                    target, damage, cactus, armor,
                    (float) target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));

            boolean match = Math.abs(expectedLoss - actualLoss) < 0.01f;
            source.sendSuccess(() -> Component.literal(String.format(
                    "[ArmorRestore] damage=%.2f armor=%d | expected(old)=%.2f | modern formula would be=%.2f | actual health loss=%.2f -> %s",
                    damage, armor, expectedLoss, modernLoss, actualLoss, match ? "PASS" : "FAIL")), false);
            return match ? 1 : 0;
        } finally {
            armorAttribute.setBaseValue(savedBase);
        }
    }

    private DamageCheckCommand() {
    }
}

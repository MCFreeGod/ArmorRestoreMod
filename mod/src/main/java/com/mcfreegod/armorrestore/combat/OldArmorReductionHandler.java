package com.mcfreegod.armorrestore.combat;

import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Substitutes the modern armor damage reduction with the pre-1.9 formula.
 *
 * <p>Registered at {@link EventPriority#HIGH} so other mods' ARMOR reduction
 * modifiers (usually at NORMAL/LOW) still run after ours and can adjust the
 * final value. The modifier is a pure substitution: it never reads the vanilla
 * reduction argument, never calls {@code setNewDamage} and never cancels events.</p>
 */
public final class OldArmorReductionHandler {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!ArmorRestoreConfig.SPEC.isLoaded() || !ArmorRestoreConfig.ENABLED.get()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        // DamageContainer holds no entity reference, so capture the entity of THIS
        // damage sequence; the lambda must not outlive the container (it is popped
        // at the end of LivingEntity#hurtServer).
        event.addReductionModifier(DamageContainer.Reduction.ARMOR,
                (container, vanillaReduction) ->
                        replaceVanillaArmorReduction(entity, container, vanillaReduction));
    }

    /**
     * At this point {@link DamageContainer#getNewDamage()} still contains the damage
     * BEFORE the armor reduction is subtracted ({@code setReduction} applies the
     * reduction only after the whole modifier chain has run), so it is the correct
     * input for the old formula. {@code vanillaReduction} is deliberately ignored
     * below: we replace, not rescale, the modern value.
     *
     * <p>Important: NeoForge's patched {@code actuallyHurt} records the ARMOR
     * reduction unconditionally; for {@code bypasses_armor} damage (fall, drown,
     * magic, on_fire, ...) vanilla simply records 0. The modifier chain still runs,
     * so the old formula must apply the same gate itself and pass the untouched
     * value through — plain armor never reduces those damage types.</p>
     */
    private float replaceVanillaArmorReduction(LivingEntity entity, DamageContainer container, float vanillaReduction) {
        if (container.getSource().is(DamageTypeTags.BYPASSES_ARMOR)) {
            return vanillaReduction;
        }
        float damageBeforeArmor = container.getNewDamage();
        float armorFraction = OldArmorReduction.armorFraction(
                entity.getArmorValue(),
                ArmorRestoreConfig.ARMOR_CLAMP_MIN.get(),
                ArmorRestoreConfig.ARMOR_CLAMP_MAX.get());
        if (ArmorRestoreConfig.BREACH_INTEGRATION.get()) {
            armorFraction = applyBreach(entity, container.getSource(), armorFraction);
        }
        float damageAfterArmor = OldArmorReduction.damageAfterArmor(damageBeforeArmor, armorFraction);
        return damageBeforeArmor - damageAfterArmor;
    }

    /**
     * Keeps the modern Breach enchantment functional on top of the old formula,
     * mirroring {@code CombatRules#getDamageAfterAbsorb}: the weapon's
     * {@code minecraft:armor_effectiveness} enchantment effects may lower the armor
     * fraction, and the result is clamped to [0, 1] exactly like vanilla.
     */
    private static float applyBreach(LivingEntity entity, DamageSource source, float armorFraction) {
        ItemStack weapon = source.getWeaponItem();
        if (weapon != null && entity.level() instanceof ServerLevel serverLevel) {
            return Mth.clamp(
                    EnchantmentHelper.modifyArmorEffectiveness(serverLevel, weapon, entity, source, armorFraction),
                    0.0f, 1.0f);
        }
        return armorFraction;
    }
}

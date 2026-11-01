package com.mcfreegod.armorrestore.mixin;

import com.mcfreegod.armorrestore.combat.OldArmorReduction;
import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the modern armor damage reduction with the pre-1.9 formula on Fabric.
 *
 * <p>NeoForge exposes {@code DamageContainer.addReductionModifier}, which lets the
 * NeoForge build substitute the armor reduction without any bytecode patching. Fabric's
 * API has no equivalent hook, so this Mixin injects at the head of
 * {@link LivingEntity#getDamageAfterArmorAbsorb(DamageSource, float)} and cancels the
 * vanilla body, returning the old formula result instead.</p>
 *
 * <p><b>Why {@code HEAD} and not {@code RETURN}:</b> the vanilla body stores the reduced
 * value back into the local variable slot holding the {@code damage} parameter
 * ({@code fstore_2} before {@code freturn}). Mixin reads parameters from those slots, so
 * at a {@code RETURN} injection point the {@code damage} argument is already the vanilla
 * result — using it as input would reduce the damage twice. Injecting at {@code HEAD}
 * guarantees the original, unmodified incoming damage.</p>
 *
 * <p>The vanilla body's two side effects are replicated here so behaviour stays identical:
 * the {@code bypasses_armor} gate (fall/drown/magic/on-fire keep their damage untouched)
 * and the {@code hurtArmor} call that applies armor durability damage. {@code hurtArmor}
 * is dispatched virtually, so {@code Player}'s equipment-damage override still runs.</p>
 *
 * <p>{@code Player} does not override this method (verified against the 26.3 jar), so a
 * single injection covers players and every other living entity.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityArmorMixin {

    @Shadow
    protected abstract void hurtArmor(DamageSource source, float damage);

    @Inject(method = "getDamageAfterArmorAbsorb", at = @At("HEAD"), cancellable = true)
    private void armorrestore$replaceArmorReduction(DamageSource source, float damage,
                                                    CallbackInfoReturnable<Float> cir) {
        ArmorRestoreConfig config = ArmorRestoreConfig.get();
        if (!config.enabled) {
            return; // vanilla computes the modern reduction
        }
        if (source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            return; // vanilla returns the damage unchanged; plain armor must not reduce it
        }

        LivingEntity self = (LivingEntity) (Object) this;

        // Vanilla applies armor durability damage before reducing, using the pre-reduction
        // damage value. Replicate that here since we cancel the vanilla body below.
        this.hurtArmor(source, damage);

        float armorFraction = OldArmorReduction.armorFraction(
                self.getArmorValue(), config.armorClampMin, config.armorClampMax);
        if (config.breachIntegration) {
            armorFraction = applyBreach(self, source, armorFraction);
        }
        cir.setReturnValue(OldArmorReduction.damageAfterArmor(damage, armorFraction));
    }

    /**
     * Keeps the modern Breach enchantment functional on top of the old formula, mirroring
     * {@code CombatRules#getDamageAfterAbsorb}: the weapon's {@code minecraft:armor_effectiveness}
     * enchantment effects may lower the armor fraction, clamped to [0, 1] exactly like vanilla.
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

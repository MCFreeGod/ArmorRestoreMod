package com.mcfreegod.armorrestore.combat;

/**
 * Pure implementation of the pre-1.9 ("pre combat update") armor damage reduction formula.
 *
 * <p>Deliberately free of any Minecraft classes so it can be unit tested without the game:</p>
 *
 * <pre>
 * armorFraction     = clamp(armor, clampMin, clampMax) / 25
 * damageAfterArmor  = damage * (1 - armorFraction)
 * armorReduction    = damage - damageAfterArmor
 * </pre>
 *
 * <p>Every armor point removes 4% of the incoming damage; the vanilla full diamond set
 * (20 armor points) caps the reduction at 80%.</p>
 *
 * <p>Armor Toughness is intentionally absent from this API: the old mechanic must not
 * read {@code ARMOR_TOUGHNESS}, and the absence of the parameter enforces that.</p>
 *
 * <p>This class is intentionally duplicated (source-identical) between the NeoForge and
 * Fabric projects of this mod: it has no platform dependency, and keeping it a copy
 * avoids a multi-project build for a single stable file.</p>
 */
public final class OldArmorReduction {
    public static final int DEFAULT_CLAMP_MIN = 0;
    public static final int DEFAULT_CLAMP_MAX = 20;
    public static final float ARMOR_FRACTION_DIVIDER = 25.0f;

    private OldArmorReduction() {
    }

    public static int clampArmor(int armor, int clampMin, int clampMax) {
        return Math.max(clampMin, Math.min(armor, clampMax));
    }

    public static float armorFraction(int armor, int clampMin, int clampMax) {
        return clampArmor(armor, clampMin, clampMax) / ARMOR_FRACTION_DIVIDER;
    }

    public static float damageAfterArmor(float damage, float armorFraction) {
        return damage * (1.0f - armorFraction);
    }

    /** The amount of damage removed by the old armor formula: {@code damage - damageAfterArmor}. */
    public static float armorReduction(float damage, int armor, int clampMin, int clampMax) {
        return damage - damageAfterArmor(damage, armorFraction(armor, clampMin, clampMax));
    }
}

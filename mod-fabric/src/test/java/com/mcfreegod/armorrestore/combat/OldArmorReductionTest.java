package com.mcfreegod.armorrestore.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix test for the restored pre-1.9 armor formula, per the implementation contract.
 * The pure math class has no Minecraft dependency, so these tests run on plain JUnit.
 */
class OldArmorReductionTest {

    private static final float EPSILON = 1.0e-4f;
    private static final int MIN = OldArmorReduction.DEFAULT_CLAMP_MIN;
    private static final int MAX = OldArmorReduction.DEFAULT_CLAMP_MAX;

    private static final int[] ARMOR_VALUES = {0, 1, 5, 10, 15, 20, 21, 25, 30};
    private static final float[] DAMAGE_VALUES = {1.0f, 10.0f, 100.0f};

    @Test
    void fullMatrixMatchesContract() {
        for (int armor : ARMOR_VALUES) {
            for (float damage : DAMAGE_VALUES) {
                int clamped = OldArmorReduction.clampArmor(armor, MIN, MAX);
                // armor 21/25/30 must be clamped back to 20 -> 80% reduction, never more.
                assertEquals(Math.min(armor, 20), (int) clamped, "armor " + armor + " clamp");

                float expectedAfter = damage * (1.0f - clamped * 0.04f);
                float actualAfter = OldArmorReduction.damageAfterArmor(
                        damage, OldArmorReduction.armorFraction(armor, MIN, MAX));
                assertEquals(expectedAfter, actualAfter, EPSILON,
                        "armor=" + armor + " damage=" + damage + " damageAfterArmor");

                float expectedReduction = damage - expectedAfter;
                float actualReduction = OldArmorReduction.armorReduction(damage, armor, MIN, MAX);
                assertEquals(expectedReduction, actualReduction, EPSILON,
                        "armor=" + armor + " damage=" + damage + " armorReduction");
            }
        }
    }

    @Test
    void armorZeroIsFullyTransparent() {
        assertEquals(1.0f, OldArmorReduction.damageAfterArmor(1.0f, OldArmorReduction.armorFraction(0, MIN, MAX)), EPSILON);
        assertEquals(10.0f, OldArmorReduction.damageAfterArmor(10.0f, OldArmorReduction.armorFraction(0, MIN, MAX)), EPSILON);
        assertEquals(100.0f, OldArmorReduction.damageAfterArmor(100.0f, OldArmorReduction.armorFraction(0, MIN, MAX)), EPSILON);
    }

    @Test
    void armor5reducesExactly20Percent() {
        assertEquals(2.0f, OldArmorReduction.armorReduction(10.0f, 5, MIN, MAX), EPSILON);
        assertEquals(0.2f, OldArmorReduction.armorReduction(1.0f, 5, MIN, MAX), EPSILON);
        assertEquals(20.0f, OldArmorReduction.armorReduction(100.0f, 5, MIN, MAX), EPSILON);
    }

    @Test
    void armor10reducesExactly40Percent() {
        assertEquals(4.0f, OldArmorReduction.armorReduction(10.0f, 10, MIN, MAX), EPSILON);
        assertEquals(0.4f, OldArmorReduction.armorReduction(1.0f, 10, MIN, MAX), EPSILON);
        assertEquals(40.0f, OldArmorReduction.armorReduction(100.0f, 10, MIN, MAX), EPSILON);
    }

    @Test
    void armor15reducesExactly60Percent() {
        assertEquals(6.0f, OldArmorReduction.armorReduction(10.0f, 15, MIN, MAX), EPSILON);
        assertEquals(0.6f, OldArmorReduction.armorReduction(1.0f, 15, MIN, MAX), EPSILON);
        assertEquals(60.0f, OldArmorReduction.armorReduction(100.0f, 15, MIN, MAX), EPSILON);
    }

    @Test
    void armor20reducesExactly80Percent() {
        assertEquals(8.0f, OldArmorReduction.armorReduction(10.0f, 20, MIN, MAX), EPSILON);
        assertEquals(0.8f, OldArmorReduction.armorReduction(1.0f, 20, MIN, MAX), EPSILON);
        assertEquals(80.0f, OldArmorReduction.armorReduction(100.0f, 20, MIN, MAX), EPSILON);
    }

    @Test
    void armorAbove20IsClampedTo80Percent() {
        for (int armor : new int[]{21, 25, 30, 100, Integer.MAX_VALUE}) {
            assertEquals(0.8f, OldArmorReduction.armorFraction(armor, MIN, MAX), EPSILON, "armor=" + armor);
            // 10 damage at 80% reduction: 8 removed, 2 remain. Never more than 80%.
            assertEquals(8.0f, OldArmorReduction.armorReduction(10.0f, armor, MIN, MAX), EPSILON, "armor=" + armor);
            assertTrue(OldArmorReduction.damageAfterArmor(10.0f, OldArmorReduction.armorFraction(armor, MIN, MAX)) >= 1.99f);
        }
    }

    /**
     * Armor Toughness must not influence the old formula at all. The formula API has no
     * toughness parameter by design; this test pins the value the modern (toughness-aware)
     * formula WOULD have produced for the same inputs, proving the old math ignores it.
     */
    @Test
    void toughnessHasNoInfluence() {
        // Old formula, armor=20, damage=10: 80% reduction -> 2.0 damage remain.
        float oldResult = OldArmorReduction.damageAfterArmor(10.0f, OldArmorReduction.armorFraction(20, MIN, MAX));
        assertEquals(2.0f, oldResult, EPSILON);
        // Modern reference for contrast (armor=20, damage=10, toughness=0):
        // realArmor = clamp(20 - 10/(2+0/4), 20*0.2, 20) = 15 -> damage * (1 - 15/25) = 4.0.
        float modernToughness0 = 10.0f * (1.0f - (float) Math.min(
                Math.max(20 - 10 / 2.0, 20 * 0.2), 20) / 25.0f);
        assertEquals(4.0f, modernToughness0, EPSILON); // sanity: modern differs from old
    }

    @Test
    void zeroDamageStaysZero() {
        for (int armor : ARMOR_VALUES) {
            assertEquals(0.0f, OldArmorReduction.armorReduction(0.0f, armor, MIN, MAX), EPSILON);
        }
    }

    @Test
    void customClampBoundsAreRespected() {
        // A tightened clamp of [0, 15] caps armor 20 at 60% reduction (4 of 10 remain).
        assertEquals(0.6f, OldArmorReduction.armorFraction(20, 0, 15), EPSILON);
        assertEquals(6.0f, OldArmorReduction.armorReduction(10.0f, 20, 0, 15), EPSILON);
        assertEquals(4.0f, OldArmorReduction.damageAfterArmor(10.0f, OldArmorReduction.armorFraction(20, 0, 15)), EPSILON);
    }
}

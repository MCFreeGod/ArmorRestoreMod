package com.mcfreegod.armorrestore.tooltip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

/**
 * Tests the tooltip filter against the exact component shapes vanilla produces:
 * an attribute line is a translatable whose first argument is the formatted value and
 * whose second argument is the (translatable) attribute name.
 */
class ToughnessTooltipFilterTest {
    private static final String ARMOR = "attribute.name.armor";
    private static final String TOUGHNESS = "attribute.name.armor_toughness";

    /** "+2 [armor toughness]" as built by ItemAttributeModifiers.Display.Default. */
    private static Component toughnessLine(String value) {
        return Component.translatable("attribute.modifier.plus.0", value, Component.translatable(TOUGHNESS));
    }

    private static Component armorLine(String value) {
        return Component.translatable("attribute.modifier.plus.0", value, Component.translatable(ARMOR));
    }

    @Test
    void removesToughnessLineAndKeepsArmorLine() {
        List<Component> lines = new ArrayList<>(List.of(
                Component.literal("Diamond Chestplate"),
                Component.empty(),
                Component.translatable("item.modifiers.chest"),
                armorLine("8"),
                toughnessLine("2")));

        ToughnessTooltipFilter.removeToughnessLines(lines, TOUGHNESS);

        assertEquals(4, lines.size());
        assertTrue(containsKey(lines, ARMOR));
        assertFalse(containsKey(lines, TOUGHNESS));
    }

    @Test
    void removesToughnessOnEveryArmorSlot() {
        for (String slot : List.of("item.modifiers.head", "item.modifiers.chest", "item.modifiers.legs", "item.modifiers.feet")) {
            List<Component> lines = new ArrayList<>(List.of(
                    Component.translatable(slot),
                    armorLine("3"),
                    toughnessLine("2")));

            ToughnessTooltipFilter.removeToughnessLines(lines, TOUGHNESS);

            assertEquals(2, lines.size(), slot);
            assertTrue(containsKey(lines, ARMOR), slot);
        }
    }

    @Test
    void leavesUnrelatedLinesUntouched() {
        List<Component> lines = new ArrayList<>(List.of(
                Component.literal("Diamond Chestplate"),
                Component.translatable("item.modifiers.chest"),
                Component.translatable("attribute.modifier.plus.0", "2", Component.translatable("attribute.name.armor_knockback_resistance")),
                Component.translatable("enchantment.minecraft.protection"),
                Component.translatable("item.durability", 500, 528)));

        ToughnessTooltipFilter.removeToughnessLines(lines, TOUGHNESS);

        assertEquals(5, lines.size());
    }

    @Test
    void findsAttributeNameNestedAsSibling() {
        MutableComponent line = Component.translatable("attribute.modifier.plus.0", "2", Component.empty())
                .append(Component.translatable(TOUGHNESS));
        List<Component> lines = new ArrayList<>(List.of(line));

        ToughnessTooltipFilter.removeToughnessLines(lines, TOUGHNESS);

        assertTrue(lines.isEmpty());
    }

    @Test
    void matchesBareAttributeNameLine() {
        List<Component> lines = new ArrayList<>(List.of(Component.translatable(TOUGHNESS)));

        ToughnessTooltipFilter.removeToughnessLines(lines, TOUGHNESS);

        assertTrue(lines.isEmpty());
    }

    @Test
    void doesNotMatchOnTranslatedText() {
        // A locale-translated literal that happens to read "盔甲韧性 +2" must not be touched:
        // matching is by translation key, never by rendered text.
        List<Component> lines = new ArrayList<>(List.of(
                Component.literal("盔甲韧性 +2"),
                Component.literal("armor toughness +2")));

        ToughnessTooltipFilter.removeToughnessLines(lines, TOUGHNESS);

        assertEquals(2, lines.size());
    }

    @Test
    void emptyListIsUntouched() {
        List<Component> lines = new ArrayList<>();

        ToughnessTooltipFilter.removeToughnessLines(lines, TOUGHNESS);

        assertTrue(lines.isEmpty());
    }

    private static boolean containsKey(List<Component> lines, String key) {
        for (Component line : lines) {
            if (ToughnessTooltipFilter.containsTranslation(line, key)) {
                return true;
            }
        }
        return false;
    }
}

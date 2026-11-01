package com.mcfreegod.armorrestore.tooltip;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Removes the "armor toughness" line from item tooltips.
 *
 * <p>The restored old formula ignores armor toughness completely, so the vanilla
 * "+X armor toughness" line would describe a stat that no longer affects damage.</p>
 *
 * <p>The line is identified by the translation key of its <em>argument</em>, not by the
 * modifier id. Since 1.21.2 vanilla armor declares armor, armor toughness and knockback
 * resistance under a single modifier id ({@code minecraft:armor.&lt;type&gt;}), so NeoForge's
 * {@code GatherSkippedAttributeTooltipsEvent#skipId} would drop the armor line as well.</p>
 *
 * <p>Kept free of platform dependencies so both the NeoForge and the Fabric hook can share it.</p>
 */
public final class ToughnessTooltipFilter {
    private ToughnessTooltipFilter() {
    }

    /**
     * Removes every tooltip line that mentions the given attribute.
     *
     * <p>An item whose tooltip is hidden entirely is returned by vanilla as an immutable
     * list, which cannot be mutated. Such a list never carries an attribute line, so the
     * read-only pass below decides whether a mutation is needed at all; only the mutable
     * list built by the tooltip renderer can contain one.</p>
     *
     * @param lines                  tooltip lines, filtered in place
     * @param toughnessDescriptionId the translation key of the armor toughness attribute
     */
    public static void removeToughnessLines(List<Component> lines, String toughnessDescriptionId) {
        boolean present = false;
        for (Component line : lines) {
            if (containsTranslation(line, toughnessDescriptionId)) {
                present = true;
                break;
            }
        }
        if (present) {
            lines.removeIf(line -> containsTranslation(line, toughnessDescriptionId));
        }
    }

    /**
     * Walks a component and its arguments/siblings looking for a translation key. Tooltip
     * lines nest the attribute name as an argument ("+2 [armor toughness]"), so a plain
     * {@code getString()} comparison would be locale-dependent and could false-positive.
     *
     * <p>Public so the runtime self test can assert on real tooltip lines.</p>
     */
    public static boolean containsTranslation(Component component, String key) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            if (key.equals(translatable.getKey())) {
                return true;
            }
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component nested && containsTranslation(nested, key)) {
                    return true;
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            if (containsTranslation(sibling, key)) {
                return true;
            }
        }
        return false;
    }
}

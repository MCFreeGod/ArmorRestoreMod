package com.mcfreegod.armorrestore.tooltip;

import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Drops the armor toughness line from item tooltips while the old formula is active.
 *
 * <p>{@link ItemTooltipEvent} fires after every line (including the attribute block) has been
 * gathered, which is the only point where the toughness line can be removed without also
 * touching the armor line — see {@link ToughnessTooltipFilter} for why the attribute-level
 * skip hooks cannot be used.</p>
 *
 * <p>Only vanilla-formatted lines are affected; a mod that renders toughness through a custom
 * tooltip component is left alone.</p>
 */
public final class ArmorToughnessTooltipHandler {

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (!ArmorRestoreConfig.SPEC.isLoaded()
                || !ArmorRestoreConfig.ENABLED.get()
                || !ArmorRestoreConfig.HIDE_ARMOR_TOUGHNESS.get()) {
            return;
        }
        ToughnessTooltipFilter.removeToughnessLines(
                event.getToolTip(),
                Attributes.ARMOR_TOUGHNESS.value().getDescriptionId());
    }
}

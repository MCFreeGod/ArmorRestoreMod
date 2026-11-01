package com.mcfreegod.armorrestore.client;

import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import com.mcfreegod.armorrestore.tooltip.ToughnessTooltipFilter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Client-only entrypoint: drops the armor toughness line from item tooltips.
 *
 * <p>Lives in a separate entrypoint because {@link ItemTooltipCallback} is a client API —
 * registering it from the main entrypoint would load client classes on a dedicated server.</p>
 *
 * <p>{@code ItemTooltipCallback} fires after vanilla has collected all lines, which is the only
 * point where the toughness line can be removed without also touching the armor line — see
 * {@link ToughnessTooltipFilter} for why the attribute-level skip hooks cannot be used.</p>
 */
public final class ArmorRestoreClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            ArmorRestoreConfig config = ArmorRestoreConfig.get();
            if (!config.enabled || !config.hideArmorToughness) {
                return;
            }
            ToughnessTooltipFilter.removeToughnessLines(
                    lines,
                    Attributes.ARMOR_TOUGHNESS.value().getDescriptionId());
        });
    }
}

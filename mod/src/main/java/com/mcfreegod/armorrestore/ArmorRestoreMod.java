package com.mcfreegod.armorrestore;

import com.mcfreegod.armorrestore.combat.DamageCheckCommand;
import com.mcfreegod.armorrestore.combat.OldArmorReductionHandler;
import com.mcfreegod.armorrestore.combat.RuntimeSelfTest;
import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import com.mcfreegod.armorrestore.tooltip.ArmorToughnessTooltipHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

@Mod(ArmorRestoreMod.MOD_ID)
public final class ArmorRestoreMod {
    public static final String MOD_ID = "armorrestore";

    public ArmorRestoreMod(IEventBus modEventBus, ModContainer modContainer) {
        // FML 12 renamed the config types: COMMON is now LOCAL (loaded on both
        // sides, global config directory, not synced).
        modContainer.registerConfig(ModConfig.Type.LOCAL, ArmorRestoreConfig.SPEC);

        // All game-bus listeners: the armor reduction replacement, the dev check
        // command, the opt-in runtime self test and the tooltip cleanup. No other
        // damage events are used.
        NeoForge.EVENT_BUS.register(new OldArmorReductionHandler());
        NeoForge.EVENT_BUS.register(new DamageCheckCommand());
        NeoForge.EVENT_BUS.register(new RuntimeSelfTest());
        NeoForge.EVENT_BUS.register(new ArmorToughnessTooltipHandler());
    }
}

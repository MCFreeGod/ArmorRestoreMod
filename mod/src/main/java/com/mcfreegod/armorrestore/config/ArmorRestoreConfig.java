package com.mcfreegod.armorrestore.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Minimal COMMON config for the restored old armor formula.
 * Defaults follow the implementation contract:
 * enabled=true, armorClampMin=0, armorClampMax=20, breachIntegration=true.
 */
public final class ArmorRestoreConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue ARMOR_CLAMP_MIN;
    public static final ModConfigSpec.IntValue ARMOR_CLAMP_MAX;
    public static final ModConfigSpec.BooleanValue BREACH_INTEGRATION;
    public static final ModConfigSpec.BooleanValue HIDE_ARMOR_TOUGHNESS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("oldArmor");
        ENABLED = builder
                .comment("Restore the pre-1.9 armor damage reduction formula")
                .define("enabled", true);
        ARMOR_CLAMP_MIN = builder
                .comment("Lower bound of the armor value clamp used by the old formula")
                .defineInRange("armorClampMin", 0, 0, 30);
        ARMOR_CLAMP_MAX = builder
                .comment("Upper bound of the armor value clamp used by the old formula (vanilla full diamond set = 20)")
                .defineInRange("armorClampMax", 20, 0, 30);
        BREACH_INTEGRATION = builder
                .comment("Let the modern Breach enchantment still reduce the old armor fraction")
                .define("breachIntegration", true);
        HIDE_ARMOR_TOUGHNESS = builder
                .comment("Hide the armor toughness line in item tooltips, since the old formula ignores toughness")
                .define("hideArmorToughnessTooltip", true);
        builder.pop();
        SPEC = builder.build();
    }

    private ArmorRestoreConfig() {
    }
}

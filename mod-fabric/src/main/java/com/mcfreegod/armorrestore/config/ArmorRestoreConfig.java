package com.mcfreegod.armorrestore.config;

/**
 * Fabric-side configuration.
 *
 * <p>Kept deliberately simple: a single JSON file in {@code config/armorrestore.json},
 * read once at mod init. There is no platform config framework dependency, matching the
 * "no over-engineering" requirement of the implementation contract.</p>
 */
public final class ArmorRestoreConfig {
    /** Restore the pre-1.9 armor damage reduction formula. */
    public boolean enabled = true;
    /** Lower bound of the armor value clamp used by the old formula. */
    public int armorClampMin = 0;
    /** Upper bound of the armor value clamp used by the old formula (vanilla full diamond set = 20). */
    public int armorClampMax = 20;
    /** Let the modern Breach enchantment still reduce the old armor fraction. */
    public boolean breachIntegration = true;
    /** Hide the armor toughness line in item tooltips, since the old formula ignores toughness. */
    public boolean hideArmorToughness = true;

    private static volatile ArmorRestoreConfig instance = new ArmorRestoreConfig();

    public static ArmorRestoreConfig get() {
        return instance;
    }

    static void set(ArmorRestoreConfig config) {
        instance = config;
    }
}

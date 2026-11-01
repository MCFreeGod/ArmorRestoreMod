package com.mcfreegod.armorrestore.config;

import com.mcfreegod.armorrestore.ArmorRestoreMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal JSON config loader: {@code config/armorrestore.json}. Written with defaults on
 * first launch, then read back. No config library dependency on purpose.
 */
public final class ConfigLoader {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigLoader() {
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("armorrestore.json");
        ArmorRestoreConfig config = new ArmorRestoreConfig();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                ArmorRestoreConfig read = GSON.fromJson(reader, ArmorRestoreConfig.class);
                if (read != null) {
                    config = read;
                }
            } catch (IOException | JsonSyntaxException e) {
                ArmorRestoreMod.LOGGER.error("[ArmorRestore] Could not read {}, using defaults", path, e);
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                    GSON.toJson(config, writer);
                }
            } catch (IOException e) {
                ArmorRestoreMod.LOGGER.error("[ArmorRestore] Could not write default config {}", path, e);
            }
        }
        ArmorRestoreConfig.set(config);
        ArmorRestoreMod.LOGGER.info("[ArmorRestore] Config loaded: enabled={} clamp=[{},{}] breach={} hideToughness={}",
                config.enabled, config.armorClampMin, config.armorClampMax, config.breachIntegration,
                config.hideArmorToughness);
    }
}

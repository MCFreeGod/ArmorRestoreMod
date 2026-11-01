package com.mcfreegod.armorrestore;

import com.mcfreegod.armorrestore.combat.DamageCheckCommand;
import com.mcfreegod.armorrestore.combat.RuntimeSelfTest;
import com.mcfreegod.armorrestore.config.ArmorRestoreConfig;
import com.mcfreegod.armorrestore.config.ConfigLoader;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric entrypoint. Mirrors the NeoForge mod's responsibilities: load config, register
 * the dev check command and (optionally) the runtime self test. The armor logic itself
 * lives in the Mixin, not here.
 */
public final class ArmorRestoreMod implements ModInitializer {
    public static final String MOD_ID = "armorrestore";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ConfigLoader.load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DamageCheckCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(RuntimeSelfTest::onServerStarted);
    }
}

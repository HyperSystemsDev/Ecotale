package com.ecotale.lib.vaultunlocked;

import com.hypixel.hytale.logger.HytaleLogger;
import net.cfh.vault.VaultUnlockedServicesManager;

public class VaultUnlockedPlugin {

    public static void setup(final HytaleLogger logger) {
        logger.atInfo().log("VaultUnlocked is installed, enabling VaultUnlocked support.");

        VaultUnlockedServicesManager.get().economy(new EcotaleUnlocked());
    }
}
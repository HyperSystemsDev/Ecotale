package com.ecotale.lib.cassaforte;

import com.hypixel.hytale.logger.HytaleLogger;
import it.cassaforte.api.Cassaforte;

public class CassaforteSetup {

    public static void setup(final HytaleLogger logger) {
        boolean registered = Cassaforte.registerEconomy(new EcotaleCassaforte());
        if (registered) {
            logger.atInfo().log("Cassaforte economy provider registered successfully.");
        } else {
            logger.atInfo().log("Cassaforte economy provider already registered by another plugin.");
        }
    }
}

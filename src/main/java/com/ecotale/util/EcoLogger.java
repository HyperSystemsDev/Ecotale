package com.ecotale.util;

import com.ecotale.Main;

/**
 * Conditional logging utility for Ecotale.
 * 
 * Only logs when debug mode is enabled in config.
 * Warnings and errors are always logged regardless of debug mode.
 */
public final class EcoLogger {
    
    private static final String PREFIX = "[Ecotale] ";
    
    private EcoLogger() {}
    
    /**
     * Log a debug message (only if debug mode is enabled).
     */
    public static void debug(String message) {
        if (isDebugEnabled()) {
            System.out.println(PREFIX + message);
        }
    }
    
    /**
     * Log a debug message with format (only if debug mode is enabled).
     */
    public static void debug(String format, Object... args) {
        if (isDebugEnabled()) {
            System.out.println(PREFIX + String.format(format, args));
        }
    }
    
    /**
     * Log an info message (only if debug mode is enabled).
     */
    public static void info(String message) {
        if (isDebugEnabled()) {
            System.out.println(PREFIX + message);
        }
    }
    
    /**
     * Log a warning (always logged, regardless of debug mode).
     */
    public static void warn(String message) {
        System.out.println(PREFIX + "WARNING: " + message);
    }
    
    /**
     * Log an error (always logged, regardless of debug mode).
     */
    public static void error(String message) {
        System.err.println(PREFIX + "ERROR: " + message);
    }
    
    /**
     * Log an error with exception (always logged).
     */
    public static void error(String message, Throwable t) {
        System.err.println(PREFIX + "ERROR: " + message);
        t.printStackTrace();
    }
    
    private static boolean isDebugEnabled() {
        try {
            return Main.CONFIG != null && Main.CONFIG.get().isDebugMode();
        } catch (Exception e) {
            return true; // Default to debug during startup
        }
    }
}

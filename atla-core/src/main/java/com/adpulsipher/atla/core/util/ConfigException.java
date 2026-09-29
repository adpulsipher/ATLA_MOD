package com.adpulsipher.atla.core.util;

/**
 * A human-readable problem in one of the JSON config files. The message is shown to the map
 * maker in chat on /atla reload and in the log, so it should say where the problem is.
 */
public class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

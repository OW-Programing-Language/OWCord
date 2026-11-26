package org.owcord.runtime;

import java.util.Map;

/** Optional JVM ServiceLoader SPI for additional storage backends. */
public interface DailyRewardStorageProvider {
    String name();
    DailyRewardStorage open(Map<String, String> environment);
}

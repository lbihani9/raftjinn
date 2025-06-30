package org.jinn.configs;

public class NodeConfig {
    /**
     * This parameter is used to control when the applied log entries should be dropped from memory based on the overall
     * log entry memory consumption to keep memory usage optimized.
     * <p>
     * Optional parameter
     * </p>
     */
    private int maxLogBytes = 2_000_000; // 2 MB

    /**
     * This parameter is used to control when the applied log entries should be dropped from memory based on time period
     * to keep memory usage optimized.
     * <p>
     * Optional parameter
     * </p>
     */
    private int logFlushIntervalMs = 3000; // 3 seconds

    public NodeConfig() {}

    public int getMaxLogBytes() {
        return maxLogBytes;
    }

    public void setMaxLogBytes(int maxLogBytes) {
        this.maxLogBytes = maxLogBytes;
    }

    public int getLogFlushIntervalMs() {
        return logFlushIntervalMs;
    }

    public void setLogFlushIntervalMs(int logFlushIntervalMs) {
        this.logFlushIntervalMs = logFlushIntervalMs;
    }
}

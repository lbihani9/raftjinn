package org.jinn.configs;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.File;

public class StoreConfig {
    private int maxWalSizeBytes = 10_000_000; // 10 MB
    private String walDir;

    @JsonIgnore
    private File walDirFile;

    public StoreConfig() {}

    public int getMaxWalSizeBytes() {
        return maxWalSizeBytes;
    }

    public void setMaxWalSizeBytes(int maxWalSizeBytes) {
        this.maxWalSizeBytes = maxWalSizeBytes;
    }

    public String getWalDir() {
        return walDir;
    }

    public void setWalDir(String walDir) {
        this.walDir = walDir;
        this.walDirFile = new File(walDir);

        if (!walDirFile.exists()) {
            walDirFile.mkdirs();
        }
    }

    public File getWalDirFile() {
        return walDirFile;
    }
}


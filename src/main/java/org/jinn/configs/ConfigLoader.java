package org.jinn.configs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class ConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    private final String configPath;
    private final ObjectMapper mapper;

    public ConfigLoader(String configPath) {
        this.configPath = configPath;
        this.mapper = new ObjectMapper(new YAMLFactory());
    }

    public RaftJinnConfig load() {
        File file = new File(configPath);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Config file not found at: " + configPath);
        }

        try {
            RaftJinnConfig config = mapper.readValue(file, RaftJinnConfig.class);
            logger.info("Loaded config for node {}", config.getClusterConfig().getNodeId());
            return config;

        } catch (IOException e) {
            logger.error("Failed to load config from {}: {}", configPath, e.getMessage());
            throw new RuntimeException("Could not parse config file", e);
        }
    }
}

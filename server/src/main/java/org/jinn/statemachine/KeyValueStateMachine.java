package org.jinn.statemachine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class KeyValueStateMachine {
    private static final Logger logger = LoggerFactory.getLogger(KeyValueStateMachine.class);
    private static volatile KeyValueStateMachine instance;
    private static final Object lock = new Object();

    private final Map<String, String> store = new ConcurrentHashMap<>();
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    private KeyValueStateMachine() { }

    public static KeyValueStateMachine getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new KeyValueStateMachine();
                }
            }
        }
        return instance;
    }

    public void applyCommand(String command) {
        rwLock.writeLock().lock();
        try {
            // Parse command like "SET key value"
            String[] parts = command.split("\\s+", 3);
            if (parts.length < 2) return;

            String operation = parts[0];
            String key = parts[1];

            switch (operation.toUpperCase()) {
                case "SET":
                    if (parts.length == 3) {
                        String value = parts[2];
                        store.put(key, value);
                    }
                    break;
                case "DEL":
                    store.remove(key);
                    break;
                default:
                    logger.info("Unsupported operation: {}", operation.toUpperCase());
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public String get(String key) {
        rwLock.readLock().lock();
        try {
            return store.get(key);
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
package org.jinn.server;

import com.google.protobuf.ByteString;
import org.jinn.configs.NodeConfig;
import org.jinn.persistence.store.JinnStore;
import org.jinn.raft.proto.LogEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the Raft log entries and provides a clean interface for log operations.
 * This class encapsulates all log-related logic to keep the Node class focused on Raft protocol logic.
 */
public class RaftLogManager {
    private final List<LogEntry> logEntries;
    private final JinnStore store;
    private final NodeConfig nodeConfig;
    private final Map<Integer, Integer> indexToPosition; // index -> position in list
    private int offset = 0; // base index for the current log entries

    public RaftLogManager(JinnStore store, NodeConfig nodeConfig) {
        this.logEntries = new ArrayList<>();
        this.store = store;
        this.nodeConfig = nodeConfig;
        this.indexToPosition = new HashMap<>();
    }

    /**
     * Adds a new log entry and persists it
     */
    public synchronized LogEntry appendEntry(int term, Object command) {
        int index = getNextIndex();
        LogEntry entry = LogEntry.newBuilder()
                .setTerm(term)
                .setCommand((ByteString) command)
                .setIndex(index)
                .build();

        logEntries.add(entry);
        indexToPosition.put(index, logEntries.size() - 1);
        return entry;
    }

    /**
     * Gets the next index for a new log entry
     */
    public synchronized int getNextIndex() {
        return logEntries.isEmpty() ? 0 : getLastEntry().getIndex() + 1;
    }

    /**
     * Gets the last log entry
     */
    public synchronized LogEntry getLastEntry() {
        return logEntries.isEmpty() ? null : logEntries.get(logEntries.size() - 1);
    }
    /**
     * Gets the last log index
     */
    public synchronized int getLastIndex() {
        return logEntries.isEmpty() ? -1 : getLastEntry().getIndex();
    }

    /**
     * Gets the last log term
     */
    public synchronized int getLastTerm() {
        return logEntries.isEmpty() ? 0 : getLastEntry().getTerm();
    }

    /**
     * Gets a log entry by index
     */
    public synchronized LogEntry getEntry(int index) {
        Integer position = indexToPosition.get(index);
        if (position == null || position < 0 || position >= logEntries.size()) {
            return null;
        }
        return logEntries.get(position);
    }

    /**
     * Gets the term of a log entry at the given index
     */
    public synchronized int getTermAt(int index) {
        LogEntry entry = getEntry(index);
        return entry != null ? entry.getTerm() : 0;
    }

    /**
     * Checks if the log contains an entry at the given index
     */
    public synchronized boolean hasEntry(int index) {
        return indexToPosition.containsKey(index);
    }
    /**
     * Appends multiple log entries (for replication)
     */
    public synchronized void appendEntries(List<LogEntry> entries) {
        int startPos = logEntries.size();
        logEntries.addAll(entries);

        // Update index map
        for (int i = 0; i < entries.size(); i++) {
            LogEntry entry = entries.get(i);
            indexToPosition.put(entry.getIndex(), startPos + i);
        }
    }

    /**
     * Truncates the log from the given index onwards
     */
    public synchronized void truncateFrom(int fromIndex) {
        if (fromIndex < logEntries.size()) {
            int lastIndex = getLastIndex();

            indexToPosition.entrySet().removeIf(entry -> entry.getKey() >= fromIndex);

            store.persistDeletedLogEntry(fromIndex, lastIndex);
            logEntries.subList(fromIndex, logEntries.size()).clear();
        }
    }


//    public synchronized void flushAppliedEntries(int upToIndex) {
//        if (upToIndex < 0 || upToIndex >= logEntries.size()) {
//            return;
//        }
//
//        int entriesToRemove = upToIndex;
//
//        indexToPosition.entrySet().removeIf(entry -> entry.getKey() < upToIndex);
//
//        // Remove from log entries
//        logEntries.subList(0, entriesToRemove).clear();
//
//        // Update base index
//        offset -= entriesToRemove;
//
//        // Shift remaining positions in the map
//        if (!logEntries.isEmpty()) {
//            HashMap<Integer, Integer> newMap = new HashMap<>();
//            for (Map.Entry<Integer, Integer> entry : indexToPosition.entrySet()) {
//                newMap.put(entry.getKey(), entry.getValue() - entriesToRemove);
//            }
//            indexToPosition.clear();
//            indexToPosition.putAll(newMap);
//        }
//    }

    /**
     * Gets all log entries from the given index onwards
     */
    public synchronized List<LogEntry> getEntriesFrom(int fromIndex) {
        if (fromIndex >= logEntries.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(logEntries.subList(fromIndex, logEntries.size()));
    }

    /**
     * Gets the number of log entries
     */
    public int size() {
        return logEntries.size();
    }

    /**
     * Checks if the log is empty
     */
    public boolean isEmpty() {
        return logEntries.isEmpty();
    }

    /**
     * Gets all log entries (for debugging/testing)
     */
    public List<LogEntry> getAllEntries() {
        return new ArrayList<>(logEntries);
    }

//    public synchronized void checkAndFlushBySize() {
//        long totalBytes = calculateLogBytes();
//        if (totalBytes > nodeConfig.getMaxLogBytes()) {
//            // Find how many entries to flush to get under the limit
//            int entriesToFlush = calculateEntriesToFlush(totalBytes);
//            if (entriesToFlush > 0) {
//                flushAppliedEntries(entriesToFlush - 1);
//            }
//        }
//    }
//
//    private long calculateLogBytes() {
//        long totalBytes = 0;
//        for (LogEntry entry : logEntries) {
//            // Calculate size: term (4 bytes) + index (4 bytes) + command size
//            totalBytes += 8 + entry.getCommand().size();
//        }
//        return totalBytes;
//    }
//
//    private int calculateEntriesToFlush(long currentBytes) {
//        long targetBytes = nodeConfig.getMaxLogBytes() / 2; // Flush to 50% of max
//        long bytesToRemove = currentBytes - targetBytes;
//
//        int entriesToFlush = 0;
//        long removedBytes = 0;
//
//        for (int i = 0; i < logEntries.size() && removedBytes < bytesToRemove; i++) {
//            LogEntry entry = logEntries.get(i);
//            removedBytes += 8 + entry.getCommand().size();
//            entriesToFlush++;
//        }
//
//        return entriesToFlush;
//    }
} 
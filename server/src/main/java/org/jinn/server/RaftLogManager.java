package org.jinn.server;

import com.google.protobuf.ByteString;
import org.jinn.configs.NodeConfig;
import org.jinn.persistence.store.JinnStore;
import org.jinn.raft.proto.LogEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the Raft log entries and provides a clean interface for log operations.
 */
public class RaftLogManager {
    private final List<LogEntry> logEntries;
    private final JinnStore store;
    private final NodeConfig nodeConfig;

    public RaftLogManager(JinnStore store, NodeConfig nodeConfig) {
        this.logEntries = new ArrayList<>();
        this.store = store;
        this.nodeConfig = nodeConfig;
    }

    private ByteString getByteString(Object command) {
        ByteString commandBytes;
        if (command instanceof ByteString) {
            commandBytes = (ByteString) command;
        } else if (command instanceof String) {
            commandBytes = ByteString.copyFromUtf8((String) command);
        } else {
            // Fallback: convert to string and then to ByteString
            commandBytes = ByteString.copyFromUtf8(command.toString());
        }
        return commandBytes;
    }

    /**
     * Adds a new log entry and persists it
     */
    public synchronized LogEntry appendEntry(int term, Object command) {
        int index = getLastIndex() + 1;

        ByteString commandBytes = getByteString(command);
        LogEntry entry = LogEntry.newBuilder()
                .setTerm(term)
                .setCommand(commandBytes)
                .setIndex(index)
                .build();

        logEntries.add(entry);
        store.persistLogEntry(entry);
        return entry;
    }

    /**
     * Gets the last log entry
     */
    public synchronized LogEntry getLastEntry() {
        return logEntries.isEmpty() ? null : logEntries.getLast();
    }
    /**
     * Gets the last log index
     */
    public synchronized int getLastIndex() {
        return logEntries.isEmpty() ? -1 : getLastEntry().getIndex();
    }

    /**
     * Gets a log entry by index
     */
    public synchronized LogEntry getEntry(int index) {
        if (index < 0 || index >= size()) {
            return null;
        }
        return logEntries.get(index);
    }

    /**
     * Gets the term of a log entry at the given index
     */
    public synchronized int getTermAt(int index) {
        LogEntry entry = getEntry(index);
        return entry != null ? entry.getTerm() : -1;
    }

    /**
     * Checks if the log contains an entry at the given index
     */
    public synchronized boolean hasEntry(int index) {
        return index < size();
    }
    /**
     * Appends multiple log entries (for replication)
     */
    public synchronized void appendEntries(List<LogEntry> entries) {
        for (LogEntry entry : entries) {
            store.persistLogEntry(entry);
        }
        logEntries.addAll(entries);
    }

    /**
     * Truncates the log from the given index onwards
     */
    public synchronized void truncateFrom(int fromIndex) {
        if (fromIndex < logEntries.size()) {
            int lastIndex = getLastIndex();
            store.persistDeletedLogEntry(fromIndex, lastIndex);
            logEntries.subList(fromIndex, logEntries.size()).clear();
        }
    }

    /**
     * Gets all log entries from the given index onwards
     */
    public synchronized List<LogEntry> getEntriesFrom(int fromIndex) {
        return getEntriesFrom(fromIndex, logEntries.size());
    }

    public synchronized List<LogEntry> getEntriesFrom(int fromIndex, int toIndex) {
        if (fromIndex >= logEntries.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(logEntries.subList(fromIndex, toIndex));
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
} 
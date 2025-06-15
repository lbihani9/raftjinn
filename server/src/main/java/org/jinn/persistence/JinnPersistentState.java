package org.jinn.persistence;

import org.jinn.raft.proto.LogEntry;

import java.util.Queue;

public class JinnPersistentState {
    private final int currentTerm;
    private final int commitIndex;
    private final String votedFor;
    private final Queue<LogEntry> entries;

    public JinnPersistentState(int currentTerm, int commitIndex, String votedFor, Queue<LogEntry> entries) {
        this.currentTerm = currentTerm;
        this.commitIndex = commitIndex;
        this.votedFor = votedFor;
        this.entries = entries;
    }

    public int getCurrentTerm() {
        return currentTerm;
    }

    public int getCommitIndex() {
        return commitIndex;
    }

    public String getVotedFor() {
        return votedFor;
    }

    public Queue<LogEntry> getEntries() {
        return entries;
    }
}

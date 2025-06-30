package org.jinn.persistence.store;

import org.jinn.persistence.JinnPersistentState;
import org.jinn.raft.proto.LogEntry;

public interface JinnStore {
    boolean persistTerm(int term);

    boolean persistCommitIndex(int commitIndex);

    boolean persistVotedFor(String candidateId, int term);

    boolean persistLogEntry(LogEntry entry);

    boolean persistDeletedLogEntry(int fromIndex, int toIndex);

    JinnPersistentState buildState();

    LogEntry getLastLogEntry();
}

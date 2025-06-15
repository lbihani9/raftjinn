package org.jinn.persistence.schema.jsonl;

public class CommitIndexData {
    private int commitIndex;

    // Default constructor for Jackson
    public CommitIndexData() {}

    public CommitIndexData(int commitIndex) {
        this.commitIndex = commitIndex;
    }

    public int getCommitIndex() {
        return commitIndex;
    }

    public void setCommitIndex(int commitIndex) {
        this.commitIndex = commitIndex;
    }
}
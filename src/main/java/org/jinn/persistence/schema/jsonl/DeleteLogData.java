package org.jinn.persistence.schema.jsonl;

public class DeleteLogData {
    private int fromIndex;
    private int toIndex;

    // Default constructor for Jackson
    public DeleteLogData() {}

    public DeleteLogData(int from, int to) {
        this.fromIndex = from;
        this.toIndex = to;
    }

    public int getFromIndex() {
        return fromIndex;
    }

    public void setFromIndex(int fromIndex) {
        this.fromIndex = fromIndex;
    }

    public int getToIndex() {
        return toIndex;
    }

    public void setToIndex(int toIndex) {
        this.toIndex = toIndex;
    }
}
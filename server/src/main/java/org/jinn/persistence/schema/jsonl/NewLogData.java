package org.jinn.persistence.schema.jsonl;

import com.google.protobuf.ByteString;
import org.jinn.raft.proto.LogEntry;

public class NewLogData {
    private int index;
    private int term;
    private ByteString command;

    // Default constructor for Jackson
    public NewLogData() {}

    public NewLogData(LogEntry entry) {
        this.index = entry.getIndex();
        this.term = entry.getTerm();
        this.command = entry.getCommand();
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public ByteString getCommand() {
        return command;
    }

    public void setCommand(ByteString command) {
        this.command = command;
    }
}
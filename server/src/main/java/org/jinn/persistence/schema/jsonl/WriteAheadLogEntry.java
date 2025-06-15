package org.jinn.persistence.schema.jsonl;

import org.jinn.persistence.LogType;

public class WriteAheadLogEntry {
    private final LogType type;
    private final Object data;

    public WriteAheadLogEntry(LogType type, Object data) {
        this.type = type;
        this.data = data;
    }

    public LogType getType() {
        return type;
    }

    public Object getData() {
        return data;
    }
}

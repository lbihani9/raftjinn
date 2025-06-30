package org.jinn.persistence.schema.jsonl;

public class TermData {
    private int term;

    // Default constructor for Jackson
    public TermData() {}

    public TermData(int term) {
        this.term = term;
    }

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }
}
package org.jinn.persistence.schema.jsonl;

public class VoteData {
    private int term;
    private String candidateId;

    // Default constructor for Jackson
    public VoteData() {}

    public VoteData(int term, String candidateId) {
        this.term = term;
        this.candidateId = candidateId;
    }

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(String candidateId) {
        this.candidateId = candidateId;
    }
}
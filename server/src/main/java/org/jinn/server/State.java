package org.jinn.server;

public enum State {
    LEADER,
    FOLLOWER,
    CANDIDATE,
    CATCHING_UP
}

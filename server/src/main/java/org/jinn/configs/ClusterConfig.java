package org.jinn.configs;

import java.util.Map;

public class ClusterConfig {
    private String nodeId;
    private Map<String, NodeAddress> members;
    private Mode startupMode;

    public ClusterConfig() {}

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public Map<String, NodeAddress> getMembers() {
        return members;
    }

    public void setMembers(Map<String, NodeAddress> members) {
        this.members = members;
    }

    public Mode getStartupMode() {
        return startupMode;
    }

    public void setStartupMode(Mode startupMode) {
        this.startupMode = startupMode;
    }
}

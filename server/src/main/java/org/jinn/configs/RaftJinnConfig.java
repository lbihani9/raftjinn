package org.jinn.configs;

public class RaftJinnConfig {
    private ClusterConfig clusterConfig;
    private StoreConfig storeConfig;
    private NodeConfig nodeConfig;

    public RaftJinnConfig() {}

    public ClusterConfig getClusterConfig() {
        return clusterConfig;
    }

    public void setClusterConfig(ClusterConfig clusterConfig) {
        this.clusterConfig = clusterConfig;
    }

    public StoreConfig getStoreConfig() {
        return storeConfig;
    }

    public void setStoreConfig(StoreConfig storeConfig) {
        this.storeConfig = storeConfig;
    }

    public NodeConfig getNodeConfig() {
        return nodeConfig;
    }

    public void setNodeConfig(NodeConfig nodeConfig) {
        this.nodeConfig = nodeConfig;
    }
}

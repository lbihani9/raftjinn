package org.jinn.server;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.jinn.configs.NodeAddress;
import org.jinn.configs.NodeConfig;
import org.jinn.configs.RaftJinnConfig;
import org.jinn.persistence.store.JsonlStore;
import org.jinn.persistence.store.JinnStore;
import org.jinn.raft.proto.*;
import org.jinn.statemachine.KeyValueStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class RaftService extends RaftServiceGrpc.RaftServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(RaftService.class);
    private static final long LEADER_CACHE_TTL_MS = 5000; // 5 seconds

    private final Node node;
    private final Map<String, RaftServiceGrpc.RaftServiceStub> stubs;
    private final KeyValueStateMachine stateMachine = KeyValueStateMachine.getInstance();
    private final RaftJinnConfig config;

    private volatile String cachedLeaderHint = null;
    private volatile int roundRobinIndex = 0;
    private volatile long lastLeaderCacheTime = 0;

    RaftService(RaftJinnConfig config) {
        this.config = config;
        this.stubs = new HashMap<>();

        Map<String, NodeAddress> peers = config.getClusterConfig().getMembers();
        String nodeId = config.getClusterConfig().getNodeId();
        for (Map.Entry<String, NodeAddress> peer: peers.entrySet()) {
            String peerId = peer.getKey();
            NodeAddress peerAddress = peer.getValue();
            String peerConnectionString = peerAddress.getHost() + ":" + peerAddress.getPort();

            if (!nodeId.equals(peerId)) {
                ManagedChannel channel = ManagedChannelBuilder.forTarget(peerConnectionString)
                        .usePlaintext()
                        .build();

                stubs.put(peerId, RaftServiceGrpc.newStub(channel));
            }
        }

        this.node = new Node(this, config);
    }

    public void sendVoteRequest(String nodeId, VoteRequest request) {
        RaftServiceGrpc.RaftServiceStub stub = stubs.get(nodeId);
        if (stub != null) {
            logger.trace("[{}] Sending VoteRequest to {}, term={}", node.getId(), nodeId, request.getTerm());
            stub.requestVote(request, new VoteResponseObserver(nodeId));
        } else {
            logger.warn("[{}] No stub found for node {}", node.getId(), nodeId);
        }
    }

    public void sendAppendEntryRequest(String nodeId, AppendEntryRequest request) {
        RaftServiceGrpc.RaftServiceStub stub = stubs.get(nodeId);
        if (stub != null) {
            logger.trace("[{}] Sending AppendEntryRequest to {}, term={}, entries={}", node.getId(), nodeId, request.getTerm(), request.getEntriesCount());
            stub.appendEntries(request, new AppendEntryResponseObserver(nodeId));
        } else {
            logger.warn("[{}] No stub found for node {}", node.getId(), nodeId);
        }
    }

    private class VoteResponseObserver implements StreamObserver<VoteResponse> {
        private final String targetNodeId;

        VoteResponseObserver(String targetNodeId) {
            this.targetNodeId = targetNodeId;
        }

        @Override
        public void onNext(VoteResponse response) {
            logger.trace("[{}] Received VoteResponse from {}, hasVoted={}, term={}", node.getId(), response.getVoterId(), response.getHasVoted(), response.getTerm());
            synchronized (node) {
                node.handleVoteResponse(response);
            }
        }

        @Override
        public void onError(Throwable t) {
            String errorMsg = t instanceof StatusRuntimeException
                    ? ((StatusRuntimeException) t).getStatus().getCode().toString()
                    : t.getClass().getSimpleName();
            logger.warn("[{}] VoteRequest to {} failed: {}", node.getId(), targetNodeId, errorMsg);

            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Full error details for VoteRequest to {}: {}", node.getId(), targetNodeId, t.getMessage(), t);
            }
        }

        @Override
        public void onCompleted() { }
    }

    private class AppendEntryResponseObserver implements StreamObserver<AppendEntryResponse> {
        private final String targetNodeId;

        AppendEntryResponseObserver(String targetNodeId) {
            this.targetNodeId = targetNodeId;
        }

        @Override
        public void onNext(AppendEntryResponse response) {
            logger.trace("[{}] Received AppendEntryResponse from {}, isReplicated={}, term={}", node.getId(), response.getFollowerId(), response.getIsReplicated(), response.getTerm());
            synchronized (node) {
                node.handleAppendEntryResponse(response);
            }
        }

        @Override
        public void onError(Throwable t) {
            String errorMsg = t instanceof StatusRuntimeException
                    ? ((StatusRuntimeException) t).getStatus().getCode().toString()
                    : t.getClass().getSimpleName();
            logger.warn("[{}] AppendEntryRequest to {} failed: {}", node.getId(), targetNodeId, errorMsg);

            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Full error details for AppendEntryRequest to {}: {}", node.getId(), targetNodeId, t.getMessage(), t);
            }
        }

        @Override
        public void onCompleted() { }
    }

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        responseObserver.onNext(node.handleVoteRequest(request));
        responseObserver.onCompleted();
    }

    @Override
    public void appendEntries(AppendEntryRequest request, StreamObserver<AppendEntryResponse> responseObserver) {
        responseObserver.onNext(node.handleAppendEntryRequest(request));
        responseObserver.onCompleted();
    }

    public void shutdown() throws InterruptedException {
        node.safeShutdown();
        for (RaftServiceGrpc.RaftServiceStub stub : stubs.values()) {
            ((ManagedChannel) stub.getChannel()).shutdown().awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        try {
            PingResponse response = PingResponse.newBuilder()
                    .setNodeId(node.getId())
                    .setState(node.getState().toString())
                    .setCurrentTerm(node.getCurrentTerm())
                    .setCommitIndex(node.getCommitIndex())
                    .setResponseTimestamp(System.currentTimeMillis())
                    .setClusterInfo(buildClusterInfo())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error handling ping request", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void clientWrite(ClientWriteRequest request, StreamObserver<ClientWriteResponse> responseObserver) {
        if (node.getState() != State.LEADER) {
            String leaderHint = findLeaderHint();
            responseObserver.onNext(ClientWriteResponse.newBuilder()
                    .setSuccess(false)
                    .setLeaderHint(leaderHint)
                    .setErrorMessage("Not leader")
                    .setRequestId(request.getRequestId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        // Create command string
        String command = String.format("SET %s %s", request.getKey(), request.getValue());

        // Add to log and replicate
        CompletableFuture<Boolean> future = node.addLogEntry(command);
        future.thenAccept(success -> {
            ClientWriteResponse response = ClientWriteResponse.newBuilder()
                    .setSuccess(success)
                    .setRequestId(request.getRequestId())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }).exceptionally(throwable -> {
            ClientWriteResponse response = ClientWriteResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(throwable.getMessage())
                    .setRequestId(request.getRequestId())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return null;
        });
    }

    @Override
    public void clientRead(ClientReadRequest request, StreamObserver<ClientReadResponse> responseObserver) {
        if (node.getState() != State.LEADER) {
            String leaderHint = findLeaderHint();
            responseObserver.onNext(ClientReadResponse.newBuilder()
                    .setSuccess(false)
                    .setLeaderHint(leaderHint)
                    .setErrorMessage("Not leader")
                    .setRequestId(request.getRequestId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        String value = stateMachine.get(request.getKey());

        ClientReadResponse response = ClientReadResponse.newBuilder()
                .setSuccess(true)
                .setValue(value != null ? value : "")
                .setRequestId(request.getRequestId())
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private String buildClusterInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Cluster size: ").append(node.getClusterNodes().size());
        info.append(", Node ID: ").append(node.getId());
        info.append(", State: ").append(node.getState());
        info.append(", Term: ").append(node.getCurrentTerm());
        return info.toString();
    }

    private String findLeaderHint() {
        // If we have recent leader information, use it
        String cachedLeader = getCachedLeaderHint();
        if (cachedLeader != null) {
            return cachedLeader;
        }

        // Try to find leader by pinging all nodes
        String discoveredLeader = discoverLeaderByPing();
        if (discoveredLeader != null) {
            cacheLeaderHint(discoveredLeader);
            return discoveredLeader;
        }

        // Use round-robin from cluster nodes as fallback
        return getRoundRobinNode();
    }

    private String getCachedLeaderHint() {
        long now = System.currentTimeMillis();
        if (cachedLeaderHint != null && (now - lastLeaderCacheTime) < LEADER_CACHE_TTL_MS) {
            logger.debug("Using cached leader hint: {}", cachedLeaderHint);
            return cachedLeaderHint;
        }
        return null;
    }

    private void cacheLeaderHint(String leaderHint) {
        this.cachedLeaderHint = leaderHint;
        this.lastLeaderCacheTime = System.currentTimeMillis();
        logger.debug("Cached leader hint: {}", leaderHint);
    }

    private String discoverLeaderByPing() {
        logger.debug("Attempting to discover leader by pinging cluster nodes");

        Map<String, NodeAddress> members = config.getClusterConfig().getMembers();

        for (Map.Entry<String, NodeAddress> entry : members.entrySet()) {
            String nodeId = entry.getKey();
            NodeAddress address = entry.getValue();

            if (nodeId.equals(node.getId())) {
                continue;
            }

            try {
                String connectionString = address.getHost() + ":" + address.getPort();
                ManagedChannel tempChannel = ManagedChannelBuilder.forTarget(connectionString)
                        .usePlaintext()
                        .build();

                RaftServiceGrpc.RaftServiceBlockingStub tempStub = RaftServiceGrpc.newBlockingStub(tempChannel);

                PingRequest pingRequest = PingRequest.newBuilder()
                        .setClientId("leader-discovery-" + node.getId())
                        .setTimestamp(System.currentTimeMillis())
                        .build();

                PingResponse response = tempStub.ping(pingRequest);

                // Check if this node is the leader
                if (State.LEADER == State.valueOf(response.getState())) {
                    logger.info("Discovered leader: {} at {}", nodeId, connectionString);
                    tempChannel.shutdown();
                    return connectionString;
                }

                tempChannel.shutdown();
            } catch (Exception e) {
                logger.debug("Failed to ping node {} for leader discovery: {}", nodeId, e.getMessage());
            }
        }

        logger.warn("Could not discover leader by pinging cluster nodes");
        return null;
    }

    private String getRoundRobinNode() {
        Set<String> clusterNodes = node.getClusterNodes();
        if (clusterNodes.isEmpty()) {
            logger.error("No cluster nodes available for round-robin selection");
            return null;
        }

        String[] nodeArray = clusterNodes.toArray(new String[0]);
        String selectedNode = nodeArray[roundRobinIndex % nodeArray.length];
        roundRobinIndex = (roundRobinIndex + 1) % nodeArray.length;

        Map<String, NodeAddress> members = config.getClusterConfig().getMembers();
        NodeAddress address = members.get(selectedNode);
        if (address != null) {
            String connectionString = address.getHost() + ":" + address.getPort();
            logger.debug("Using round-robin fallback: {} at {}", selectedNode, connectionString);
            return connectionString;
        }

        logger.error("Could not get address for round-robin selected node: {}", selectedNode);
        return null;
    }
}

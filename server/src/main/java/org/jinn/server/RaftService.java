package org.jinn.server;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.jinn.raft.proto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RaftService extends RaftServiceGrpc.RaftServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(RaftService.class);
    private final Node node;
    private final Map<String, RaftServiceGrpc.RaftServiceStub> stubs;

    RaftService(String nodeId, Map<String, String> clusterNodes) {
        this.stubs = new HashMap<>();
        for (Map.Entry<String, String> node: clusterNodes.entrySet()) {
            ManagedChannel channel = ManagedChannelBuilder.forTarget(node.getValue())
                    .usePlaintext()
                    .build();

            stubs.put(node.getKey(), RaftServiceGrpc.newStub(channel));
        }

        this.node = new Node(nodeId, stubs.keySet(), this);
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
}

package org.jinn.server;

import com.google.protobuf.ByteString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jinn.raft.proto.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Node {
    private static final Logger logger = LogManager.getLogger(Node.class);
    private static final int MIN_ELECTION_TIME_MS = 200;
    private static final int MAX_ELECTION_TIME_MS = 600;
    private static final int HEARTBEAT_TIME_MS = 50;
    private static final int PENDING_FUTURE_CLEANUP_TIME_MS = 60;
    private static final Random random = new Random();

    private final RaftService service;

    private final String id;
    private volatile State state;
    private final Set<String> clusterNodes;

    // persistent states
    private final AtomicInteger currentTerm;
    private volatile String votedFor;
    private final List<LogEntry> logEntries;

    // volatile states
    // index of the highest log entry known to be committed. The majority has acknowledged.
    private final AtomicInteger commitIndex;

    // index of the highest log entry applied to the state machine
    private final AtomicInteger lastApplied;

    // leader-specific volatile state
    // For each follower, the index of the next log entry to send.
    private final Map<String, Integer> nextIndex;

    // For each follower, the index of the highest log entry known to be replicated.
    private final Map<String, Integer> matchIndex;

    private final ConcurrentSkipListMap<Integer, CompletableFuture<Boolean>> replicationFutures = new ConcurrentSkipListMap<>();

    private final Set<String> votersInFavour;

    private final ScheduledExecutorService electionTimer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> electionTask;

    private final ScheduledExecutorService heartbeatTimer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;

    private final ScheduledExecutorService futureCleanupTimer = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> futureCleanupTask;

    Node(String id, Set<String> nodeIds, RaftService service) {
        this.id = id;
        this.service = service;
        this.state = State.FOLLOWER;
        this.votedFor = null;
        this.logEntries = new ArrayList<>();
        this.currentTerm = new AtomicInteger(0);
        this.commitIndex = new AtomicInteger(-1);
        this.lastApplied = new AtomicInteger(-1);
        this.votersInFavour = new HashSet<>();
        this.nextIndex = new HashMap<>();
        this.matchIndex = new HashMap<>();
        this.clusterNodes = new HashSet<>(nodeIds);
        clusterNodes.remove(id);
        logger.info("Node {} initialized as FOLLOWER in term {}, cluster size: {}", id, currentTerm, clusterNodes.size() + 1);
        startElectionTimeout(0);
    }

    private void initializePendingFutureCleanupTimeout() {
        if (futureCleanupTask == null) {
            futureCleanupTask = futureCleanupTimer.scheduleWithFixedDelay(
                this::maybeCommit,
                0,
                PENDING_FUTURE_CLEANUP_TIME_MS,
                TimeUnit.MILLISECONDS
            );
        }
    }

    private void clearPendingFutureCleanupTimeout() {
        if (futureCleanupTask != null) {
            futureCleanupTask.cancel(false);
            futureCleanupTask = null;

            maybeCommit();

            for (Map.Entry<Integer, CompletableFuture<Boolean>> entry : replicationFutures.entrySet()) {
                if (!entry.getValue().isDone()) {
                    entry.getValue().complete(false);
                }
            }
            replicationFutures.clear();
        }
    }

    private void startElectionTimeout(int startupDelay) {
        cancelElectionTimeout();
        int randomTimeout = MIN_ELECTION_TIME_MS + random.nextInt(MAX_ELECTION_TIME_MS - MIN_ELECTION_TIME_MS + 1);
        logger.debug("Node {} starting election timeout for {} ms", id, randomTimeout);
        electionTask = electionTimer.schedule(
            this::shouldStartElection,
            randomTimeout + startupDelay,
            TimeUnit.MILLISECONDS
        );
    }

    private void startHeartbeats() {
        cancelHeartbeatTimeout();
        logger.debug("Node {} starting heartbeat timer with interval {} ms", id, HEARTBEAT_TIME_MS);
        heartbeatTask = heartbeatTimer.scheduleWithFixedDelay(
            this::sendHeartBeats,
            0,
            HEARTBEAT_TIME_MS,
            TimeUnit.MILLISECONDS
        );
    }

    private void cancelElectionTimeout() {
        if (electionTask != null) {
            electionTask.cancel(true);
            electionTask = null;
        }
    }

    private void cancelHeartbeatTimeout() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }
    }

    private void shouldStartElection() {
        if (state != State.LEADER) {
            startElection();
        }
    }

    private void sendHeartBeats() {
        if (state != State.LEADER) {
            return;
        }

        for (String nodeId: clusterNodes) {
            if (Objects.equals(nodeId, id)) {
                continue;
            }
            int nxtIndex = nextIndex.get(nodeId);
            int prevLogIndex = nxtIndex - 1;
            int prevLogTerm = (prevLogIndex >= 0 && prevLogIndex < logEntries.size())
                    ? logEntries.get(prevLogIndex).getTerm() : 0;

            List<LogEntry> deltaLogEntries = new ArrayList<>();
            int lastIndex = logEntries.size();
            for (int i=nxtIndex; i < lastIndex; ++i) {
                deltaLogEntries.add(logEntries.get(i));
            }

            AppendEntryRequest appendEntry = AppendEntryRequest.newBuilder()
                    .setTerm(currentTerm.intValue())
                    .setLeaderCommit(commitIndex.intValue())
                    .setPrevLogTerm(prevLogTerm)
                    .setPrevLogIndex(prevLogIndex)
                    .addAllEntries(deltaLogEntries)
                    .build();

//            logger.info("Node {} sending logs to Node {}", id, nodeId);
            service.sendAppendEntryRequest(nodeId, appendEntry);
        }
    }

    // managed by non-leaders
    private void startElection() {
        votersInFavour.clear();
        state = State.CANDIDATE;
        currentTerm.addAndGet(1);
        votedFor = id;
        votersInFavour.add(id);

        logger.info("Node {} starting election for term {}", id, currentTerm);

        int lastLogIndex = logEntries.size() - 1;
        int lastLogTerm = lastLogIndex >= 0 ? logEntries.get(lastLogIndex).getTerm() : 0;
        VoteRequest voteRequest = VoteRequest.newBuilder()
                .setCandidateId(id)
                .setTerm(currentTerm.intValue())
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        for (String peer : clusterNodes) {
            if (!peer.equals(id)) {
                logger.debug("Node {} sending vote request to {} for term {}", id, peer, currentTerm);
                service.sendVoteRequest(peer, voteRequest);
            }
        }

        startElectionTimeout(0);
    }

    // Managed by all nodes
    VoteResponse handleVoteRequest(VoteRequest request) {
        logger.debug("Node {} received vote request from {} for term {}", id, request.getCandidateId(), request.getTerm());

        if (request.getTerm() < currentTerm.intValue()) {
            logger.debug("Node {} rejected vote request from {} (stale term: {})", id, request.getCandidateId(), request.getTerm());
            return VoteResponse.newBuilder()
                    .setVoterId(id)
                    .setTerm(currentTerm.intValue())
                    .setHasVoted(false)
                    .build();
        }

        if (request.getTerm() > currentTerm.intValue()) {
            logger.info("Node {} updating term {} -> {} from vote request", id, currentTerm, request.getTerm());
            demote(State.FOLLOWER, request.getTerm());
            startElectionTimeout(0);
        }

        boolean canVote = (votedFor == null || votedFor.equals(request.getCandidateId()));
        int lastLogIndex = logEntries.size() - 1;
        int lastLogTerm = lastLogIndex >= 0 ? logEntries.get(lastLogIndex).getTerm() : 0;

        int candidateLastLogIndex = request.getLastLogIndex();
        int candidateLastLogTerm = request.getLastLogTerm();

        boolean isCandidateUptoDate = (candidateLastLogTerm > lastLogTerm) || (candidateLastLogIndex >= lastLogIndex && candidateLastLogTerm == lastLogTerm);

        if (canVote && isCandidateUptoDate) {
            logger.info("Node {} voting for {} in term {}", id, request.getCandidateId(), currentTerm);
            votedFor = request.getCandidateId();
            startElectionTimeout(0);
            return VoteResponse.newBuilder()
                    .setVoterId(id)
                    .setTerm(request.getTerm())
                    .setHasVoted(true)
                    .build();
        }

        logger.debug("Node {} rejected vote request from {} (up-to-date check failed)", id, request.getCandidateId());
        return VoteResponse.newBuilder()
                .setVoterId(id)
                .setTerm(request.getTerm())
                .setHasVoted(false)
                .build();
    }

    // Managed by candidates
    public void handleVoteResponse(VoteResponse response) {
        logger.debug("Node {} received vote response from {}, granted: {}, term: {}", 
                    id, response.getVoterId(), response.getHasVoted(), response.getTerm());

        if (state != State.CANDIDATE || response.getTerm() < currentTerm.intValue()) {
            return;
        }

        if (response.getTerm() > currentTerm.intValue()) {
            logger.info("Node {} stepping down: received higher term {} from {}", 
                       id, response.getTerm(), response.getVoterId());
            demote(State.FOLLOWER, response.getTerm());
            startElectionTimeout(0);
            return;
        }

        if (response.getHasVoted()) {
            votersInFavour.add(response.getVoterId());
            if (hasMajorityVotes()) {
                promoteLeader();
            }

            logger.debug("Node {} received vote from {}, total votes: {}", id, response.getVoterId(), votersInFavour.size());
        }
    }

    private boolean hasMajorityVotes() {
        return votersInFavour.size() >= (clusterNodes.size() + 1) / 2;
    }

    private void promoteLeader() {
        logger.info("Node {} becoming leader for term {}", id, currentTerm);
        state = State.LEADER;
        cancelElectionTimeout();

        for (String nodeId: clusterNodes) {
            nextIndex.put(nodeId, logEntries.size());
            matchIndex.put(nodeId, -1);
        }

        initializePendingFutureCleanupTimeout();
        startHeartbeats();
    }

    private void demote(State to, int term) {
        if (state == State.LEADER) {
            cancelHeartbeatTimeout();
            clearPendingFutureCleanupTimeout();
        }

        state = to;
        currentTerm.set(term);
        votersInFavour.clear();
        votedFor = null;
    }

    // Managed by non-leaders
    AppendEntryResponse handleAppendEntryRequest(AppendEntryRequest request) {
        logger.debug("Node {} received append entry request from {} for term {}", 
                    id, request.getLeaderId(), request.getTerm());
        
        int prevLogIndex = request.getPrevLogIndex();
        int prevLogTerm = request.getPrevLogTerm();

        startElectionTimeout(0);

        if (request.getTerm() < currentTerm.intValue()) {
            return AppendEntryResponse.newBuilder()
                    .setFollowerId(id)
                    .setTerm(currentTerm.intValue())
                    .setPrevLogTerm(prevLogTerm)
                    .setMatchIndex(prevLogIndex)
                    .setIsReplicated(false)
                    .build();
        }

        if (request.getTerm() > currentTerm.intValue()) {
            demote(State.FOLLOWER, request.getTerm());
        }

        if (prevLogIndex >= 0) {
            // Since prevLogIndex is the last index sent to this follower, then ideally if this same
            // index is sent again for replication, we should reject the request.
            if (prevLogIndex >= logEntries.size()) {
                return AppendEntryResponse.newBuilder()
                        .setFollowerId(id)
                        .setTerm(currentTerm.intValue())
                        .setPrevLogTerm(prevLogTerm)
                        .setMatchIndex(prevLogIndex)
                        .setIsReplicated(false)
                        .build();
            }

            if (logEntries.get(prevLogIndex).getTerm() != prevLogTerm) {
                return AppendEntryResponse.newBuilder()
                        .setFollowerId(id)
                        .setTerm(currentTerm.intValue())
                        .setPrevLogTerm(prevLogTerm)
                        .setMatchIndex(prevLogIndex)
                        .setIsReplicated(false)
                        .build();
            }
        }

        // handling conflicting writes
        int logInsertIndex = prevLogIndex + 1;
        for (int i = 0; i < request.getEntriesCount(); i++) {
            int entryIndex = logInsertIndex + i;
            LogEntry newEntry = request.getEntries(i);

            if (entryIndex < logEntries.size()) {
                if (logEntries.get(entryIndex).getTerm() != newEntry.getTerm()) {
                    // Remove conflicting entries and all that follow
                    logEntries.subList(entryIndex, logEntries.size()).clear();
                    logEntries.add(newEntry);
                }
            } else {
                logEntries.add(newEntry);
            }
        }

        if (request.getLeaderCommit() > commitIndex.intValue()) {
            int lastNewEntryIndex = logEntries.size() - 1;
            commitIndex.set(Math.min(request.getLeaderCommit(), lastNewEntryIndex));
            applyCommittedEntries();
        }

        // Acknowledging the largest index commited.
        int matchIndex = logInsertIndex + request.getEntriesCount() - 1;
        return AppendEntryResponse.newBuilder()
                .setFollowerId(id)
                .setTerm(currentTerm.intValue())
                .setPrevLogTerm(prevLogTerm)
                .setMatchIndex(Math.max(matchIndex, prevLogIndex))
                .setIsReplicated(true)
                .build();
    }

    // Managed by leader.
    public void handleAppendEntryResponse(AppendEntryResponse response) {
        if (state != State.LEADER) {
            return;
        }

        // Ignore stale responses
        if (response.getTerm() < currentTerm.intValue()) {
            return;
        }

        if (response.getTerm() > currentTerm.intValue()) {
            demote(State.FOLLOWER, response.getTerm());
            startElectionTimeout(0);
            return;
        }

        String followerId = response.getFollowerId();
        if (response.getIsReplicated()) {
            matchIndex.put(followerId, response.getMatchIndex());
            nextIndex.put(followerId, response.getMatchIndex() + 1);
            updateCommitIndex();
        } else {
            nextIndex.computeIfPresent(followerId, (k, v) -> Math.max(0, v - 1));
        }
    }

    private void updateCommitIndex() {
        // Find the highest index replicated on a majority of servers
        for (int index = logEntries.size() - 1; index > commitIndex.intValue(); index--) {
            if (logEntries.get(index).getTerm() == currentTerm.intValue()) { // Only commit entries from current term
                int replicationCount = 1; // Count self
                for (int matchIdx : matchIndex.values()) {
                    if (matchIdx >= index) {
                        replicationCount++;
                    }
                }

                if (replicationCount > (clusterNodes.size() + 1) / 2) {
                    commitIndex.set(index);
                    applyCommittedEntries();
                    break;
                }
            }
        }
    }

    private void applyCommittedEntries() {
        while (lastApplied.intValue() < commitIndex.intValue()) {
            lastApplied.addAndGet(1);
            if (lastApplied.intValue() < logEntries.size()) {
                LogEntry entry = logEntries.get(lastApplied.intValue());
                logger.info("Applied entry ({}): {}", id, entry);
            }
        }
    }

    // for client requests
    public CompletableFuture<Boolean> addLogEntry(Object command) {
        if (state != State.LEADER) {
            return CompletableFuture.completedFuture(false);
        }

        LogEntry entry = LogEntry.newBuilder()
                .setTerm(currentTerm.intValue())
                .setCommand((ByteString) command)
                .setIndex(logEntries.size())
                .build();

        logEntries.add(entry);
        return waitForReplication(entry.getIndex());
    }

    private CompletableFuture<Boolean> waitForReplication(int index) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        replicationFutures.put(index, future);
        return future;
    }

    private void maybeCommit() {
        int newCommitIndex = commitIndex.intValue();
        NavigableMap<Integer, CompletableFuture<Boolean>> readyFutures = replicationFutures.headMap(newCommitIndex, true);
        for (Map.Entry<Integer, CompletableFuture<Boolean>> entry : readyFutures.entrySet()) {
            if (!entry.getValue().isDone()) {
                entry.getValue().complete(true);
            }
        }
        readyFutures.clear();
    }

    public State getState() {
        return state;
    }

    public int getCurrentTerm() {
        return currentTerm.intValue();
    }

    public int getCommitIndex() {
        return commitIndex.intValue();
    }

    public int getLastApplied() {
        return lastApplied.intValue();
    }

    public String getId() {
        return id;
    }

    public void safeShutdown() {
        logger.info("Node {} initiating safe shutdown", id);
        cancelElectionTimeout();
        cancelHeartbeatTimeout();
        electionTimer.shutdown();
        heartbeatTimer.shutdown();
        logger.info("Node {} shutdown complete", id);
    }
}

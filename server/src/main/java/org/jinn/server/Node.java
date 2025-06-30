package org.jinn.server;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jinn.configs.ClusterConfig;
import org.jinn.configs.Mode;
import org.jinn.configs.RaftJinnConfig;
import org.jinn.persistence.JinnPersistentState;
import org.jinn.persistence.store.JinnStore;
import org.jinn.persistence.store.JsonlStore;
import org.jinn.raft.proto.*;
import org.jinn.statemachine.KeyValueStateMachine;

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

    private final KeyValueStateMachine kvStore = KeyValueStateMachine.getInstance();
    private final RaftService service;
    private final JinnStore store;

    private final String id;
    private volatile State state;
    private final Set<String> clusterNodes;

    // persistent states
    private final AtomicInteger currentTerm;
    private volatile String votedFor;
    private final RaftLogManager logManager;

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

    Node(RaftService service, RaftJinnConfig config) {
        ClusterConfig clusterConfig = config.getClusterConfig();
        Mode startupMode = clusterConfig.getStartupMode();

        this.id = clusterConfig.getNodeId();
        this.service = service;
        this.votedFor = null;
        this.currentTerm = new AtomicInteger(0);
        this.commitIndex = new AtomicInteger(-1);
        this.lastApplied = new AtomicInteger(-1);
        this.votersInFavour = new HashSet<>();
        this.nextIndex = new HashMap<>();
        this.matchIndex = new HashMap<>();
        this.clusterNodes = new HashSet<>(clusterConfig.getMembers().keySet());
        this.store = new JsonlStore(config.getStoreConfig());
        this.logManager = new RaftLogManager(store, config.getNodeConfig());
        this.state = State.CATCHING_UP;

        loadPersistentState();
    }

    private void loadPersistentState() {
        try {
            JinnPersistentState persistentState = store.buildState();
            this.currentTerm.set(persistentState.getCurrentTerm());
            this.votedFor = persistentState.getVotedFor();
            this.commitIndex.set(persistentState.getCommitIndex());
            
            // Load log entries into LogManager
            logManager.appendEntries(new ArrayList<>(persistentState.getEntries()));

            logger.info("Node {} loaded persistent state: term={}, votedFor={}, commitIndex={}, logEntries={}",
                       id, currentTerm.get(), votedFor, commitIndex.get(), logManager.size());

            this.state = State.FOLLOWER;
            startElectionTimeout();
        } catch (Exception e) {
            logger.warn("Failed to load persistent state for node {}, starting fresh: {}", id, e.getMessage());
            throw new RuntimeException(e);
        }
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

    private void startElectionTimeout() {
        cancelElectionTimeout();

        int randomTimeout = MIN_ELECTION_TIME_MS + random.nextInt(MAX_ELECTION_TIME_MS - MIN_ELECTION_TIME_MS + 1);
        logger.trace("Node {} starting election timeout for {} ms", id, randomTimeout);

        int termStarted = currentTerm.intValue();
        electionTask = electionTimer.schedule(
            () -> shouldStartElection(termStarted),
            randomTimeout,
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

    synchronized private void shouldStartElection(int termStarted) {
        cancelElectionTimeout();
        if (state != State.FOLLOWER && state != State.CANDIDATE) {
            return;
        }

        if (termStarted != currentTerm.intValue()) {
            startElectionTimeout();
            return;
        }

        startElection();
    }

    synchronized private void startElection() {
        votersInFavour.clear();
        state = State.CANDIDATE;
        currentTerm.addAndGet(1);
        int savedCurrentTerm = currentTerm.intValue();
        votedFor = id;
        votersInFavour.add(id);
        store.persistTerm(savedCurrentTerm);
        store.persistVotedFor(id, savedCurrentTerm);

        logger.info("Node {} starting election for term {}", id, currentTerm);

        int lastLogIndex = logManager.getLastIndex();
        int lastLogTerm = logManager.getTermAt(lastLogIndex);
        
        VoteRequest voteRequest = VoteRequest.newBuilder()
                .setCandidateId(id)
                .setTerm(savedCurrentTerm)
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        for (String peer : clusterNodes) {
            if (!peer.equals(id)) {
                logger.debug("Node {} sending vote request to {} for term {}", id, peer, currentTerm);
                service.sendVoteRequest(peer, voteRequest);
            }
        }

        startElectionTimeout();
    }

    synchronized VoteResponse handleVoteRequest(VoteRequest request) {
        logger.debug("Node {} received vote request from {} for term {}", id, request.getCandidateId(), request.getTerm());

        if (request.getTerm() > currentTerm.intValue()) {
            logger.info("Node {} updating term {} -> {} from vote request", id, currentTerm, request.getTerm());
            demote(State.FOLLOWER, request.getTerm());
        }

        if (request.getTerm() < currentTerm.intValue()) {
            logger.debug("Node {} rejected vote request from {} (stale term: {})", id, request.getCandidateId(), request.getTerm());
            return VoteResponse.newBuilder()
                    .setVoterId(id)
                    .setTerm(currentTerm.intValue())
                    .setHasVoted(false)
                    .build();
        }

        boolean canVote = (votedFor == null || votedFor.equals(request.getCandidateId()));
        int lastLogIndex = logManager.getLastIndex();
        int lastLogTerm = logManager.getTermAt(lastLogIndex);

        int candidateLastLogIndex = request.getLastLogIndex();
        int candidateLastLogTerm = request.getLastLogTerm();

        boolean isCandidateUptoDate = (candidateLastLogTerm > lastLogTerm) || (candidateLastLogIndex >= lastLogIndex && candidateLastLogTerm == lastLogTerm);

        if (canVote && isCandidateUptoDate) {
            logger.info("Node {} voting for {} in term {}", id, request.getCandidateId(), currentTerm);
            votedFor = request.getCandidateId();
            store.persistVotedFor(request.getCandidateId(), request.getTerm());
            startElectionTimeout();
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
    synchronized public void handleVoteResponse(VoteResponse response, int sentTerm) {
        logger.debug("Node {} received vote response from {}, granted: {}, term: {}",
                id, response.getVoterId(), response.getHasVoted(), response.getTerm());

        if (state != State.CANDIDATE) {
            return;
        }

        // Delayed response of an old election. Should be ignored.
        if (response.getTerm() < currentTerm.intValue()) {
            return;
        }

        if (response.getTerm() > currentTerm.intValue()) {
            logger.info("Node {} stepping down: received higher term {} from {}", id, response.getTerm(), response.getVoterId());
            demote(State.FOLLOWER, response.getTerm());
            return;
        }

        if (response.getHasVoted()) {
            votersInFavour.add(response.getVoterId());
            logger.debug("Node {} received vote from {}, total votes: {}", id, response.getVoterId(), votersInFavour.size());
            logger.trace("Current voters: {}", votersInFavour);
            if (hasMajorityVotes()) {
                promoteLeader();
            }
        }
    }

    synchronized private void promoteLeader() {
        cancelElectionTimeout();
        logger.info("Node {} becoming leader for term {}", id, currentTerm);
        state = State.LEADER;

        for (String nodeId: clusterNodes) {
            nextIndex.put(nodeId, logManager.getLastIndex() + 1);
            matchIndex.put(nodeId, -1);
        }

        initializePendingFutureCleanupTimeout();
        startHeartbeats();
    }

    synchronized private void demote(State to, int term) {
        if (state == State.LEADER) {
            cancelHeartbeatTimeout();
            clearPendingFutureCleanupTimeout();
        }

        state = to;
        votersInFavour.clear();
        votedFor = null;
        currentTerm.set(term);
        store.persistTerm(term);
        store.persistVotedFor(null, term);
        if (to == State.FOLLOWER) {
            startElectionTimeout();
        }
    }

    synchronized private void sendHeartBeats() {
        if (state != State.LEADER) {
            return;
        }

        int savedCurrentTerm = currentTerm.intValue();
        for (String nodeId: clusterNodes) {
            if (Objects.equals(nodeId, id)) {
                continue;
            }
            Integer idx = nextIndex.get(nodeId);
            if (idx == null) {
                logger.warn("Node {} has no nextIndex entry for follower {}, skipping", id, nodeId);
                continue;
            }
            int prevLogIndex = idx - 1;
            int prevLogTerm = logManager.getTermAt(prevLogIndex);

            List<LogEntry> deltaLogEntries = logManager.getEntriesFrom(idx);

            // TODO: Leader id can also be passed from here. We don't have to broadcast discovery messages when a client sends request to follower.
            AppendEntryRequest appendEntry = AppendEntryRequest.newBuilder()
                    .setTerm(savedCurrentTerm)
                    .setLeaderCommit(commitIndex.intValue())
                    .setPrevLogTerm(prevLogTerm)
                    .setPrevLogIndex(prevLogIndex)
                    .addAllEntries(deltaLogEntries)
                    .build();

            service.sendAppendEntryRequest(nodeId, appendEntry);
        }
    }

    synchronized AppendEntryResponse handleAppendEntryRequest(AppendEntryRequest request) {
        logger.trace("Node {} received append entry request from {} for term {}",
                    id, request.getLeaderId(), request.getTerm());
        
        int prevLogIndex = request.getPrevLogIndex();
        int prevLogTerm = request.getPrevLogTerm();

        if (request.getTerm() > currentTerm.intValue()) {
            demote(State.FOLLOWER, request.getTerm());
        }

        startElectionTimeout();

        if (request.getTerm() < currentTerm.intValue()) {
            return AppendEntryResponse.newBuilder()
                    .setFollowerId(id)
                    .setTerm(currentTerm.intValue())
                    .setPrevLogTerm(prevLogTerm)
                    .setMatchIndex(prevLogIndex)
                    .setIsReplicated(false)
                    .build();
        }

        if (state != State.FOLLOWER) {
            demote(State.FOLLOWER, request.getTerm());
        }

        startElectionTimeout();

        if (prevLogIndex >= 0) {
            if (!logManager.hasEntry(prevLogIndex)) {
                return AppendEntryResponse.newBuilder()
                        .setFollowerId(id)
                        .setTerm(currentTerm.intValue())
                        .setPrevLogTerm(prevLogTerm)
                        .setMatchIndex(prevLogIndex)
                        .setIsReplicated(false)
                        .build();
            }

            if (logManager.getTermAt(prevLogIndex) != prevLogTerm) {
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
        int logEntriesIndex = 0;
        for (; logEntriesIndex < request.getEntriesCount(); logEntriesIndex++) {
            int entryIndex = logInsertIndex + logEntriesIndex;
            LogEntry newEntry = request.getEntries(logEntriesIndex);

            if (logManager.hasEntry(entryIndex)) {
                if (logManager.getTermAt(entryIndex) != newEntry.getTerm()) {
                    // Remove conflicting entries and all that follow
                    logManager.truncateFrom(entryIndex);
                    break;
                }
            } else {
                break;
            }
        }

        if (logEntriesIndex < request.getEntriesCount()) {
            logManager.appendEntries(request.getEntriesList().subList(logEntriesIndex, request.getEntriesCount()));
        }

        if (request.getLeaderCommit() > commitIndex.intValue()) {
            int newCommitIndex = Math.min(request.getLeaderCommit(), logManager.getLastIndex());
            store.persistCommitIndex(newCommitIndex);
            commitIndex.set(newCommitIndex);
            applyCommittedEntries();
        }

        // Acknowledging the largest index committed.
        int matchIndex = logInsertIndex + request.getEntriesCount() - 1;
        return AppendEntryResponse.newBuilder()
                .setFollowerId(id)
                .setTerm(currentTerm.intValue())
                .setPrevLogTerm(prevLogTerm)
                .setMatchIndex(Math.max(matchIndex, prevLogIndex))
                .setIsReplicated(true)
                .build();
    }

    synchronized private void updateCommitIndex() {
        // Find the highest index replicated on a majority of servers
        // Updating atomically
        for (int index = logManager.getLastIndex(); index > commitIndex.intValue(); index--) {
            if (logManager.getTermAt(index) == currentTerm.intValue()) {
                int replicationCount = 1; // Count self
                for (int matchIdx : matchIndex.values()) {
                    if (matchIdx >= index) {
                        replicationCount++;
                    }
                }

                if (replicationCount >= clusterNodes.size()/2 + 1) {
                    store.persistCommitIndex(index);
                    commitIndex.set(index);
                    applyCommittedEntries();
                    break;
                }
            }
        }
    }

    synchronized private boolean hasMajorityVotes() {
        logger.debug("Quorum check: voters={}, clusterNodes={}", votersInFavour, clusterNodes);
        return votersInFavour.size() >= clusterNodes.size()/2 + 1;
    }

    // Managed by leader.
    synchronized public void handleAppendEntryResponse(AppendEntryResponse response) {
        // This will happen when the node was leader when it sent the heartbeat, but it's no longer a leader now.
        if (state != State.LEADER) {
            return;
        }

        // Ignore stale responses
        if (response.getTerm() < currentTerm.intValue()) {
            return;
        }

        if (response.getTerm() > currentTerm.intValue()) {
            demote(State.FOLLOWER, response.getTerm());
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

    synchronized private void applyCommittedEntries() {
        int savedCommitedIndex = commitIndex.intValue();
        int savedLastApplied = lastApplied.intValue();

        List<LogEntry> entries = new ArrayList<>();
        if (savedLastApplied < savedCommitedIndex) {
            entries.addAll(logManager.getEntriesFrom(savedLastApplied + 1, savedCommitedIndex + 1));
        }

        for (LogEntry entry: entries) {
            logger.debug("Applied entry ({}): {}", id, entry);
            lastApplied.addAndGet(1);
            String command = entry.getCommand().toStringUtf8();
            kvStore.applyCommand(command);
        }
    }

    synchronized public CompletableFuture<Boolean> addLogEntry(Object command) {
        if (state != State.LEADER) {
            return CompletableFuture.completedFuture(false);
        }

        LogEntry entry = logManager.appendEntry(currentTerm.intValue(), command);
        return waitForReplication(entry.getIndex());
    }

    synchronized private CompletableFuture<Boolean> waitForReplication(int index) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        replicationFutures.put(index, future);
        return future;
    }

    synchronized private void maybeCommit() {
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

    public Set<String> getClusterNodes() {
        return new HashSet<>(clusterNodes);
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

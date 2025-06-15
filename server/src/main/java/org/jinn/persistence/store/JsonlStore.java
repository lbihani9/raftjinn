package org.jinn.persistence.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.util.*;

import org.jinn.configs.StoreConfig;
import org.jinn.persistence.JinnPersistentState;
import org.jinn.persistence.schema.jsonl.*;
import org.jinn.persistence.LogType;
import org.jinn.raft.proto.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonlStore implements JinnStore {
    private static final String LOG_FILE_PATTERN = "wal-\\d+\\.jsonl";
    private static final String DEFAULT_WAL_FILE = "wal-00001.jsonl";
    private static final Logger logger = LoggerFactory.getLogger(JsonlStore.class);
    private final ObjectMapper mapper;
    private final StoreConfig storeConfig;
    private final File currentFile;

    public JsonlStore(StoreConfig storeConfig) {
        this.storeConfig = storeConfig;
        this.currentFile = getLatestWalFile();
        this.mapper = new ObjectMapper();
    }

    private File getLatestWalFile() {
        File walDir = storeConfig.getWalDirFile();

        File[] files = walDir.listFiles((dir, name) -> name.matches(LOG_FILE_PATTERN));
        if (files == null || files.length == 0) {
            File newFile = new File(walDir.getAbsolutePath(), DEFAULT_WAL_FILE);
            try {
                newFile.createNewFile();
            } catch (IOException ignored) {}
            return newFile;
        }

        // Sort files by number
        Arrays.sort(files, Comparator.comparingInt(
        f -> Integer.parseInt(
                f.getName().replaceAll("\\D", "")
            )
        ));

        return files[files.length - 1];
    }

    @Override
    public boolean persistTerm(int term) {
        return persist(new WriteAheadLogEntry(LogType.TERM, new TermData(term)));
    }

    @Override
    public boolean persistCommitIndex(int commitIndex) {
        return persist(new WriteAheadLogEntry(LogType.COMMIT_INDEX, new CommitIndexData(commitIndex)));
    }

    @Override
    public boolean persistVotedFor(String candidateId, int term) {
        return persist(new WriteAheadLogEntry(LogType.VOTE, new VoteData(term, candidateId)));
    }

    @Override
    public boolean persistLogEntry(LogEntry entry) {
        return persist(new WriteAheadLogEntry(LogType.LOG_ENTRY, new NewLogData(entry)));
    }

    @Override
    public boolean persistDeletedLogEntry(int fromIndex, int toIndex) {
        return persist(new WriteAheadLogEntry(LogType.DELETE_LOG_ENTRY, new DeleteLogData(fromIndex, toIndex)));
    }

    private boolean persist(WriteAheadLogEntry wal) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(currentFile, true))) {
            String json = mapper.writeValueAsString(wal);
            writer.write(json);
            writer.newLine();
            return true;
        } catch (IOException e) {
            logger.error("Failed to persist WAL entry: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public JinnPersistentState buildState() {
        int currentTerm = 0, commitIndex = 0;
        String votedFor = null;
        TreeMap<Integer, LogEntry> entries = new TreeMap<>();

        File walDir = storeConfig.getWalDirFile();
        File[] walFiles = walDir.listFiles((dir, name) -> name.matches("wal-\\d+\\.jsonl"));
        if (walFiles == null || walFiles.length == 0) {
            logger.info("No WAL files found in {}", walDir.getAbsolutePath());
            return new JinnPersistentState(currentTerm, commitIndex, votedFor, new LinkedList<>(entries.values()));
        }

        Arrays.sort(walFiles, Comparator.comparingInt(
            f -> Integer.parseInt(
                f.getName().replaceAll("\\D", "")
            )
        ));

        for (File file: walFiles) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        JsonNode node = mapper.readTree(line);
                        String type = node.get("type").asText();
                        JsonNode dataNode = node.get("data");
                        
                        switch (type) {
                            case "TERM":
                                TermData termData = mapper.treeToValue(dataNode, TermData.class);
                                if (termData.getTerm() > currentTerm) {
                                    votedFor = null;
                                    currentTerm = termData.getTerm();
                                }
                                break;
                            case "VOTE":
                                VoteData voteData = mapper.treeToValue(dataNode, VoteData.class);
                                votedFor = voteData.getCandidateId();
                                break;
                            case "LOG_ENTRY":
                                NewLogData newLogData = mapper.treeToValue(dataNode, NewLogData.class);
                                entries.put(
                                    newLogData.getIndex(),
                                    LogEntry.newBuilder()
                                        .setCommand(newLogData.getCommand())
                                        .setIndex(newLogData.getIndex())
                                        .setTerm(newLogData.getTerm())
                                        .build()
                                );
                                break;
                            case "DELETE_LOG_ENTRY":
                                DeleteLogData deleteLogData = mapper.treeToValue(dataNode, DeleteLogData.class);
                                entries.tailMap(deleteLogData.getFromIndex()).clear();
                                break;
                            case "COMMIT":
                                CommitIndexData commitIndexData = mapper.treeToValue(dataNode, CommitIndexData.class);
                                commitIndex = commitIndexData.getCommitIndex();

                                // We're making sure that only the logs from commitIndex onwards are in memory.
                                // This avoids unnecessary memory usage.
                                entries.headMap(commitIndex).clear();
                                break;
                            default:
                                logger.warn("Unknown WAL type: {}", type);
                                break;
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to parse WAL line: {}, error: {}", line, e.getMessage());
                    }
                }
            } catch (IOException e) {
                logger.error("Failed to read WAL file {}: {}", file.getAbsolutePath(), e.getMessage());
                throw new RuntimeException("Failed to build full state from server.", e);
            }
        }

        return new JinnPersistentState(currentTerm, commitIndex, votedFor, new LinkedList<>(entries.values()));
    }

    @Override
    public LogEntry getLastLogEntry() {
        // TODO: Implement this method to return the last log entry
        // For now, return null as it's not critical for basic functionality
        return null;
    }
}

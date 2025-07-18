package org.jinn;

import org.jinn.server.RaftJinnServer;

import java.io.IOException;

/**
 * TODO:
 * 1. Snapshots & Checkpointing
 * 2. Flushing entries from memory that have already been replicated to majority followers to keep memory usage minimal. This may be fixed with snapshotting.
 * 3. Optimize RaftLogManager in terms of deletion.
 * 4. Optimize LogEntriesBytes size tracker to initiate flushing.
 * 5. Implement Client side logic to actually call "write" operation on raft cluster and perform replication.
 * 6. Test and optimize pending futures operations for client writes.
 * 7. Dynamic node addition and removal
 * 8. Separate out LogEntries logs and other states
 * 9. Support compaction
 * 10. Optimize reads and writes from/to disk.
 * 11. Read from raft cluster
 * 12. Minimalistic interactive terminal to connect with the cluster and perform SET, GET and CONNECT operations.
 * 13. Test for consistency related things.
 *
 *
 * Node startup command: mvn exec:java -Dexec.mainClass='org.jinn.RaftNodeRunner' -Dexec.args='src/main/resources/configs/node1.yml'
 * Client startup command: mvn exec:java -Dexec.mainClass="org.jinn.client.RaftClient"
 */

public class RaftNodeRunner {
    public static void main(String[] args) throws IOException, InterruptedException {
        String configPath = args[0];
        RaftJinnServer jinnServer = new RaftJinnServer(configPath);
        jinnServer.start();
        jinnServer.blockUntilShutdown();
    }
}
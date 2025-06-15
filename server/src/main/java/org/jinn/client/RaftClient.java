package org.jinn.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.jinn.raft.proto.*;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicLong;

public class RaftClient {
    private ManagedChannel channel;
    private RaftServiceGrpc.RaftServiceBlockingStub blockingStub;
    private final AtomicLong requestIdCounter = new AtomicLong(0);
    private String currentConnection = null;
    private final String clientId = "client-" + System.currentTimeMillis();

    public void connect(String connectionString) {
        try {
            if (channel != null) {
                channel.shutdown();
            }

            channel = ManagedChannelBuilder.forTarget(connectionString)
                    .usePlaintext()
                    .build();

            blockingStub = RaftServiceGrpc.newBlockingStub(channel);
            currentConnection = connectionString;

            System.out.println("Connected to: " + connectionString);
        } catch (Exception e) {
            System.err.println("Failed to connect: " + e.getMessage());
        }
    }

    public void set(String key, String value) {
        if (currentConnection == null) {
            System.err.println("Not connected to any node. Use CONNECT first to connect with a node.");
            return;
        }

        try {
            ClientWriteRequest request = ClientWriteRequest.newBuilder()
                    .setKey(key)
                    .setValue(value)
                    .setClientId(clientId)
                    .setRequestId(requestIdCounter.incrementAndGet())
                    .build();

            ClientWriteResponse response = blockingStub.clientWrite(request);

            if (response.getSuccess()) {
                System.out.println("OK");
            } else {
                if (!response.getLeaderHint().isEmpty()) {
                    System.err.println("Redirected to leader: " + response.getLeaderHint());
                    connect(response.getLeaderHint());
                    // Retry the operation
                    set(key, value);
                } else {
                    System.err.println("Error: " + response.getErrorMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    public void get(String key) {
        if (currentConnection == null) {
            System.err.println("Not connected to any node. Use CONNECT first.");
            return;
        }

        try {
            ClientReadRequest request = ClientReadRequest.newBuilder()
                    .setKey(key)
                    .setClientId(clientId)
                    .setRequestId(requestIdCounter.incrementAndGet())
                    .build();

            ClientReadResponse response = blockingStub.clientRead(request);

            if (response.getSuccess()) {
                String value = response.getValue();
                if (value.isEmpty()) {
                    System.out.println("(nil)");
                } else {
                    System.out.println(value);
                }
            } else {
                if (!response.getLeaderHint().isEmpty()) {
                    System.err.println("Redirected to leader: " + response.getLeaderHint());
                    connect(response.getLeaderHint());
                    // Retry the operation
                    get(key);
                } else {
                    System.err.println("Error: " + response.getErrorMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    public void ping() {
        if (currentConnection == null) {
            System.err.println("Not connected to any node. Use CONNECT first.");
            return;
        }

        try {
            PingRequest request = PingRequest.newBuilder()
                    .setClientId(clientId)
                    .setTimestamp(System.currentTimeMillis())
                    .build();

            PingResponse response = blockingStub.ping(request);

            System.out.println("=== Node Information ===");
            System.out.println("Node ID: " + response.getNodeId());
            System.out.println("State: " + response.getState());
            System.out.println("Current Term: " + response.getCurrentTerm());
            System.out.println("Commit Index: " + response.getCommitIndex());
            System.out.println("Cluster Info: " + response.getClusterInfo());

            long latency = response.getResponseTimestamp() - request.getTimestamp();
            System.out.println("Latency: " + latency + "ms");
            System.out.println("========================");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    public void startInteractiveMode() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("RaftJinn Client Terminal");
        System.out.println("Commands: CONNECT <host:port>, SET <key> <value>, GET <key>, PING, QUIT");

        while (true) {
            System.out.print("> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String command = parts[0].toUpperCase();

            switch (command) {
                case "CONNECT":
                    if (parts.length != 2) {
                        System.err.println("Usage: CONNECT <host:port>");
                        break;
                    }
                    connect(parts[1]);
                    break;

                case "SET":
                    if (parts.length != 3) {
                        System.err.println("Usage: SET <key> <value>");
                        break;
                    }
                    set(parts[1], parts[2]);
                    break;

                case "GET":
                    if (parts.length != 2) {
                        System.err.println("Usage: GET <key>");
                        break;
                    }
                    get(parts[1]);
                    break;

                case "PING":
                    ping();
                    break;

                case "QUIT":
                    if (channel != null) {
                        channel.shutdown();
                    }
                    System.out.println("Goodbye!");
                    return;

                default:
                    System.err.println("Unknown command: " + command);
                    System.out.println("Available commands: CONNECT, SET, GET, PING, QUIT");
                    break;
            }
        }
    }

    public static void main(String[] args) {
        RaftClient client = new RaftClient();
        client.startInteractiveMode();
    }
}
package org.jinn;

import org.jinn.server.RaftJinnServer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class RaftNodeRunner {
    public static void main(String[] args) throws IOException, InterruptedException {
        String nodeId = args[0];
        int port = Integer.parseInt(args[1]);
        String[] clusterNodes = args[2].split(",");
        Map<String, String> nodeAddresses = new HashMap<>();

        for (String node: clusterNodes) {
            String id = node.split(":")[0];
            String address = node.split(":")[1] + ":" + node.split(":")[2];
            nodeAddresses.put(id, address);
        }

        RaftJinnServer jinnServer = new RaftJinnServer(nodeId, port, nodeAddresses);
        jinnServer.start();
        jinnServer.blockUntilShutdown();
    }
}
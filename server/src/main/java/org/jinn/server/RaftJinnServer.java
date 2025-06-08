package org.jinn.server;

import io.grpc.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RaftJinnServer {
    private static final Logger logger = LogManager.getLogger(RaftJinnServer.class);
    private final int port;
    private final Server server;
    private final RaftService service;

    public RaftJinnServer(String id, int port, Map<String, String> nodes) throws IOException {
        this(Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create()), id, port, nodes);
    }

    public RaftJinnServer(ServerBuilder<?> serverBuilder, String id, int port, Map<String, String> nodes) {
        this.port = port;
        this.service = new RaftService(id, nodes);
        this.server = serverBuilder.addService(service)
                .build();
        logger.info("Initialized RaftJinnServer with ID: {}, port: {}, cluster size: {}", id, port, nodes.size());
    }

    public void start() throws IOException {
        server.start();
        logger.info("Server started, listening on port {}", port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.warn("*** shutting down gRPC server since JVM is shutting down");
            try {
                RaftJinnServer.this.stop();
            } catch (InterruptedException e) {
                logger.error("Error during shutdown", e);
            }
            logger.info("*** server shut down");
        }));
    }

    public void stop() throws InterruptedException {
        if (server != null) {
            logger.info("Initiating server shutdown");
            service.shutdown();
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
            logger.info("Server shutdown complete");
        }
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            logger.debug("Waiting for server termination");
            server.awaitTermination();
        }
    }

    public int getPort() {
        return port;
    }

    public Server getServer() {
        return server;
    }
}

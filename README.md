# 🚀 RaftJinn

RaftJinn is a durable and consistent implementation of the Raft consensus algorithm in Java — built from scratch for learning, clarity, and correctness over performance. It is not production-optimized (yet), but aims to serve as a strong reference for understanding how Raft works internally.

## ✅ Features Implemented So Far

- 🗳️ **Leader election** and consensus (heartbeat, voting, terms)
- 📦 **Log replication** with commitIndex tracking
- 🔁 **State recovery** via durable JSONL-based Write-Ahead Log
- 🧪 **Manually tested** with controlled multi-node setups
- 💻 **Basic interactive terminal** for `GET` / `SET` over Raft via gRPC

> ⚠️ No formal test suite yet — manual testing only.


## 🗳️ Raft Internal RPCs (Node ↔ Node)

These are internal gRPC calls between Raft nodes for consensus:

| Method            | Request              | Response              | Purpose                                 |
|------------------|----------------------|------------------------|-----------------------------------------|
| `requestVote()`   | `VoteRequest`        | `VoteResponse`         | Used during leader election             |
| `appendEntries()` | `AppendEntryRequest` | `AppendEntryResponse`  | Used for log replication and heartbeats |


## 🧠 Node Info API (Client → Node)

| Method   | Request       | Response       | Purpose                                                       |
|----------|---------------|----------------|----------------------------------------------------------------|
| `ping()` | `PingRequest` | `PingResponse` | Returns node info (state, term, etc.) for discovery/debugging |

Used by:
- `PING` command in CLI
- Fallback when leader is unknown

## 🔑 Client-facing KV Store APIs (Client → Leader)

These APIs allow external clients (like your CLI) to interact with the replicated state machine:

| Method          | Request              | Response              | Purpose                     | Leader Only |
|----------------|----------------------|------------------------|-----------------------------|--------------|
| `clientWrite()` | `ClientWriteRequest` | `ClientWriteResponse`  | Replicate a `SET` operation | ✅            |
| `clientRead()`  | `ClientReadRequest`  | `ClientReadResponse`   | Read a value via `GET`      | ✅            |

Behavior:
- If called on a **follower**, the response includes `leaderHint` so the client can **redirect**.
- Your CLI **automatically reconnects and retries** on redirect — 🔥🔥🔥

### 🖥️ CLI Terminal Commands (via `RaftClient.java`)

| Command             | Maps to gRPC Method | Description                          |
|---------------------|---------------------|--------------------------------------|
| `CONNECT host:port` | N/A (sets target)   | Connects to a specific node          |
| `SET key value`     | `clientWrite()`     | Writes key-value to replicated state |
| `GET key`           | `clientRead()`      | Reads value from the state machine   |
| `PING`              | `ping()`            | Checks node health & cluster info    |
| `QUIT`              | N/A                 | Exits and shuts down channel cleanly |

> (*A CLI wrapper is being built for these commands — stay tuned.*)

## 🧪 Testing

At this stage, RaftJinn has been manually tested using locally spawned nodes (3–5) with controlled startup sequences to verify:

- Leader election correctness
- Log replication across followers
- Term updates and heartbeats
- State reload after crash or restart

> Formal unit and integration test suites are in the roadmap.


## 🚧 Roadmap / TODOs

### 🔁 Raft Core Enhancements
- [ ] Snapshotting and checkpointing
- [ ] Log compaction support
- [ ] Dynamic node addition/removal
- [x] Basic write replication with client-side retries (MVP)
- [x] Leader hinting + redirect support from follower

### 🧠 Memory + Disk Optimization
- [ ] Flush log entries from memory after successful replication to majority
- [ ] Optimize `RaftLogManager` for log deletion/cleanup
- [ ] Improve read/write performance of disk I/O (WAL & recovery)

### 💬 Client + Terminal
- [x] Minimalistic interactive CLI (`CONNECT`, `SET`, `GET`, `PING`)
- [x] Automatic reconnect + retry on `leaderHint`
- [ ] Better error handling + retry strategy in terminal client

### 🧪 Testing & Reliability
- [ ] Add unit + integration test suite
- [ ] Simulate failover, crash-recovery, and log replay
- [ ] Test for consistency and quorum correctness under load
---

## ▶️ Running RaftJinn

Start a node with config:
```bash
mvn compile exec:java -Dexec.mainClass='org.jinn.RaftNodeRunner' -Dexec.args='src/main/resources/configs/node1.yml'
```

Start a client with config:
```bash
mvn compile exec:java -Dexec.mainClass="org.jinn.client.RaftClient"
```

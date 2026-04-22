# Player Messaging

A Java 21 demonstration of two player-to-player messaging models:

1. **Same-process** — two or more players exchange messages as virtual threads inside a single JVM, communicating through bounded in-memory queues.

2. **Multi-process** — two players run in separate JVM processes (or separate containers) and communicate over a TCP socket using a length-prefixed binary wire protocol.

The codebase showcases five classic GoF design patterns applied to real production concerns: **Observer**, **Decorator**, **Chain of Responsibility**, **Abstract Factory**, and **Builder**.

---

## Table of Contents

- [Requirements](#requirements)
- [Project Structure](#project-structure)
- [Quick Start](#quick-start)
  - [Same-process (one-liner)](#same-process-one-liner)
  - [Multi-process (one-liner)](#multi-process-one-liner)
- [Building Manually](#building-manually)
  - [Maven](#maven)
  - [Gradle](#gradle)
  - [Running the jars after building](#running-the-jars-after-building)
- [Running the Scenarios](#running-the-scenarios)
  - [Same-process scenario](#same-process-scenario)
  - [Multi-process scenario](#multi-process-scenario)
- [Configuration](#configuration)
- [Running the Tests](#running-the-tests)
- [Docker](#docker)
  - [Single container (same-process)](#single-container-same-process)
  - [Two containers (multi-process)](#two-containers-multi-process)
- [Architecture Overview](#architecture-overview)
- [Design Patterns](#design-patterns)
- [Design Decisions](#design-decisions)
- [Future Improvements](#future-improvements)

---

## Requirements

| Requirement | Version |
|---|---|
| Java (JDK) | **21 or later** (required for virtual threads) |
| Maven | **Not required** — the Maven Wrapper (`./mvnw`) downloads it automatically |
| Gradle | **Not required** — the Gradle Wrapper (`./gradlew`) downloads it automatically |
| Docker | Optional — only needed for the containerised runs |

Verify your Java version:

```bash
java -version
# expected: openjdk version "21.x.x" ...
```

---

## Project Structure

```
player-messaging/
├── mvnw / mvnw.cmd             # Maven Wrapper (no local Maven needed)
├── pom.xml                     # Maven build descriptor + dependency declarations
├── gradlew / gradlew.bat       # Gradle Wrapper (no local Gradle needed)
├── build.gradle                # Gradle build script (feature-equivalent to pom.xml)
├── settings.gradle             # Gradle project name declaration
├── gradle/wrapper/             # Gradle Wrapper bootstrap jar + properties
├── run-same-process.sh         # One-shot script: build + run same-process
├── run-multi-process.sh        # One-shot script: build + run multi-process (both in same terminal)
├── run-multi-process-split.sh  # Opens responder + initiator in two separate terminal windows
├── Dockerfile                  # Multi-stage Docker image
├── docker-compose.yml          # Two-container multi-process wiring
├── docker-entrypoint.sh        # Container entrypoint (selects the right jar)
└── src/
    ├── main/
    │   ├── java/com/playermessaging/player/
    │   │   ├── common/         # Shared abstractions + all design-pattern interfaces
    │   │   ├── sameprocess/    # In-memory broker, Conversation, ConversationBuilder
    │   │   └── multiprocess/   # TCP socket channel, reconnect, binary protocol
    │   └── resources/
    │       ├── configuration.properties   # All tuneable values
    │       └── logback.xml                # Logging configuration
    └── test/
        └── java/com/playermessaging/player/
            ├── common/         # Unit tests: Message, Policy, Roles, Metrics
            ├── sameprocess/    # Unit + integration tests: Broker, Conversation
            └── multiprocess/   # Socket integration tests (real loopback sockets)
```

### Key classes by package

| Package | Class | Role |
|---|---|---|
| `common` | `Message` | Immutable value object (sender, recipient, content, sentAt) |
| `common` | `MessageBroker` | Mediator interface — routes messages by name |
| `common` | `MessageChannel` | Transport abstraction (in-memory or TCP) |
| `common` | `PlayerRole` | **Strategy** — InitiatorRole / ResponderRole |
| `common` | `ConversationPolicy` | **Strategy** — FixedMessageCountPolicy |
| `common` | `PlayerComponentFactory` | **Abstract Factory** interface |
| `common` | `InitiatorComponentFactory` | **Abstract Factory** — creates InitiatorRole + policy |
| `common` | `ResponderComponentFactory` | **Abstract Factory** — creates ResponderRole |
| `common` | `MessageChannelDecorator` | **Decorator** abstract base |
| `common` | `LoggingChannelDecorator` | **Decorator** — DEBUG logs every send/receive |
| `common` | `MetricsChannelDecorator` | **Decorator** — counts sends/receives |
| `common` | `MessageHandler` | **Chain of Responsibility** handler interface |
| `common` | `ValidationHandler` | **Chain** — rejects blank sender/recipient |
| `common` | `LoggingHandler` | **Chain** — TRACE logs every published message |
| `common` | `MessageEventListener` | **Observer** — broker event subscriber interface |
| `common` | `LoggingEventListener` | **Observer** — DEBUG logs PUBLISHED/DELIVERED events |
| `common` | `MetricsEventListener` | **Observer** — atomic aggregate counters |
| `sameprocess` | `InMemoryMessageBroker` | Mediator + Observer subject + Chain host |
| `sameprocess` | `DeliveryHandler` | **Chain** terminal — enqueues into inbox |
| `sameprocess` | `Conversation` | Orchestrates N-player ring with virtual threads |
| `sameprocess` | `ConversationBuilder` | **Builder** — fluent construction of Conversation |
| `multiprocess` | `SocketChannel` | TCP channel with length-prefixed binary protocol |
| `multiprocess` | `MultiProcessMain` | Composition root — wires Decorator chain |

---

## Quick Start

> Both scripts build the project automatically on first run. **No Maven installation required.**

### Same-process (one-liner)

```bash
./run-same-process.sh
```

Runs a 2-player conversation (configurable) inside a single JVM. Both players run as virtual threads and communicate through an in-memory `BlockingQueue`.

### Multi-process (one-liner)

```bash
./run-multi-process.sh
```

Spawns two separate JVM processes — one responder listening on TCP port 9090, one initiator connecting to it — and runs the conversation over a real socket. Both processes exit cleanly when the conversation ends.


## Building Manually

The project supports **both Maven and Gradle**. Both produce the same three fat-jars; pick whichever you prefer. No local installation is required — wrappers download the correct version automatically.

### Maven

```bash
# Compile + test + package all three fat-jars
./mvnw package

# Package only, skipping tests
./mvnw package -DskipTests

# Format all Java source files in-place (google-java-format AOSP style)
./mvnw fmt:format

# Check formatting without modifying files (CI-safe)
./mvnw fmt:check
```

Fat-jars produced in **`target/`**:

| Jar | Main class | Purpose |
|---|---|---|
| `player-same-process.jar` | `SameProcessMain` | Same-process scenario |
| `player-multi-process.jar` | `MultiProcessMain` | Multi-process scenario |
| `player-benchmark.jar` | `org.openjdk.jmh.Main` | JMH throughput benchmark |

### Gradle

```bash
# Compile + test + format check + package all three fat-jars
./gradlew build

# Build only, skipping tests
./gradlew build -x test

# Clean previous output, then do a fresh build
./gradlew clean build

# Compile only
./gradlew compileJava

# Format all Java source files in-place (google-java-format AOSP style)
./gradlew spotlessApply

# Check formatting without modifying files (CI-safe)
./gradlew spotlessCheck
```

Fat-jars produced in **`build/libs/`**:

| Jar | Main class | Purpose |
|---|---|---|
| `player-same-process.jar` | `SameProcessMain` | Same-process scenario |
| `player-multi-process.jar` | `MultiProcessMain` | Multi-process scenario |
| `player-benchmark.jar` | `org.openjdk.jmh.Main` | JMH throughput benchmark |

### Build tool command equivalence

| Goal | Maven | Gradle |
|---|---|---|
| Compile | `./mvnw compile` | `./gradlew compileJava` |
| Test | `./mvnw test` | `./gradlew test` |
| Package (fat-jars) | `./mvnw package` | `./gradlew build` |
| Format code | `./mvnw fmt:format` | `./gradlew spotlessApply` |
| Check format (CI) | `./mvnw fmt:check` | `./gradlew spotlessCheck` |
| Full verify | `./mvnw verify` | `./gradlew check` |
| Clean | `./mvnw clean` | `./gradlew clean` |

### Running the jars after building

After `./gradlew build` (or `./mvnw package`), run the fat-jars directly with `java -jar`:

```bash
# Same-process — pass player names as arguments
java -jar build/libs/player-same-process.jar Alice Bob

# Multi-process — Terminal 1: start the responder first
java -jar build/libs/player-multi-process.jar responder

# Multi-process — Terminal 2: start the initiator
java -jar build/libs/player-multi-process.jar initiator

# JMH benchmarks
java -jar build/libs/player-benchmark.jar
```

> Maven equivalent paths: replace `build/libs/` with `target/` (e.g. `java -jar target/player-same-process.jar Alice Bob Charlie`)

---

## Running the Scenarios

### Same-process scenario

> **Maven build** — jars are in `target/`
> **Gradle build** — jars are in `build/libs/`

```bash
# Default: 2 players, 10 messages (from configuration.properties)
java -jar build/libs/player-same-process.jar          # Gradle
java -jar target/player-same-process.jar              # Maven

# Named players — pass any number of names as arguments
java -jar build/libs/player-same-process.jar Alice Bob Charlie
java -jar target/player-same-process.jar Alice Bob Charlie

# 4-player ring by count
java -jar build/libs/player-same-process.jar 4
java -jar target/player-same-process.jar 4

# Override any setting with -D flags (no rebuild needed)
java -Dmax.messages=25 -Dopening.message=Hi -jar build/libs/player-same-process.jar
java -Dmax.messages=25 -Dopening.message=Hi -jar target/player-same-process.jar

# Enable debug logging at runtime (shows Decorator + Chain logs)
java -Dlog.level=DEBUG -jar build/libs/player-same-process.jar
java -Dlog.level=DEBUG -jar target/player-same-process.jar
```

Each player logs its sent / received counts and one-way latency statistics when it shuts down. After all players finish, the Observer-based `MetricsEventListener` prints a broker-wide summary:

```
[BrokerMetrics] published=22  delivered=21
```

### Multi-process scenario

The two processes must be started in separate terminals (or use `run-multi-process.sh` which manages this automatically).

> **Maven build** — jars are in `target/`
> **Gradle build** — jars are in `build/libs/`

**Terminal 1 — start the responder first:**

```bash
java -jar target/player-multi-process.jar responder          # Maven
java -jar build/libs/player-multi-process.jar responder      # Gradle
# Listens on port 9090 and waits for the initiator to connect.
```

**Terminal 2 — start the initiator:**

```bash
java -jar target/player-multi-process.jar initiator          # Maven
java -jar build/libs/player-multi-process.jar initiator      # Gradle
# Connects to localhost:9090 and begins the conversation.
```

After the conversation ends, each side logs its decorator-level channel stats:

```
[Initiator | PID 12345] Channel stats — sent=11 received=10
```

**Connecting to a remote responder:**

```bash
java -Dserver.host=192.168.1.42 -jar target/player-multi-process.jar initiator        # Maven
java -Dserver.host=192.168.1.42 -jar build/libs/player-multi-process.jar initiator    # Gradle
```

**Overriding the port:**

```bash
# Maven
java -Dserver.port=8080 -jar target/player-multi-process.jar responder &
java -Dserver.port=8080 -jar target/player-multi-process.jar initiator

# Gradle
java -Dserver.port=8080 -jar build/libs/player-multi-process.jar responder &
java -Dserver.port=8080 -jar build/libs/player-multi-process.jar initiator
```

---

## Configuration

All tuneable values live in `src/main/resources/configuration.properties` and are bundled inside the fat-jar at build time. You can override **any key at runtime** with a `-D` JVM flag — no rebuild required.

| Key | Default | Description |
|---|---|---|
| `max.messages` | `10` | Messages initiator sends before ending the conversation |
| `opening.message` | `Hello` | First message sent by the initiator |
| `player.count` | `2` | Default number of players (same-process only) |
| `inbox.queue.capacity` | `16` | Max messages buffered per player inbox (backpressure) |
| `server.port` | `9090` | TCP port for the multi-process scenario |
| `server.host` | `localhost` | Host the initiator connects to |
| `reconnect.max.attempts` | `5` | Initiator retry count on connection failure |
| `reconnect.delay.ms` | `500` | Initial delay between retries (doubles on each attempt) |
| `connect.delay.ms` | `1000` | How long the initiator waits before its first connect attempt |
| `log.level` | `INFO` | Logback root log level (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`) |

**Runtime override example** (no rebuild, no config file edit):

```bash
java -Dmax.messages=50 -Dlog.level=DEBUG -jar target/player-same-process.jar 3       # Maven
java -Dmax.messages=50 -Dlog.level=DEBUG -jar build/libs/player-same-process.jar 3   # Gradle
```

**File override**: Place a `configuration.properties` file next to the jar. `ConfigLoader` checks the working directory before the classpath, so you can ship a custom properties file alongside the jar without modifying it.

---

## Running the Tests

**Maven:**

```bash
# Run all 86 tests
./mvnw test

# Run a specific test class
./mvnw test -Dtest=InMemoryMessageBrokerTest

# Run all tests in a package
./mvnw test -Dtest="com.playermessaging.player.common.*"
```

**Gradle:**

```bash
# Run all 86 tests
./gradlew test

# Run a specific test class
./gradlew test --tests "com.playermessaging.player.sameprocess.InMemoryMessageBrokerTest"

# Run all tests in a package
./gradlew test --tests "com.playermessaging.player.common.*"

# Re-run tests even if nothing has changed (Gradle caches test results)
./gradlew test --rerun-tasks

# View the HTML test report after running
open build/reports/tests/test/index.html
```

### Test coverage overview

| Test class | Tests | What it covers |
|---|---|---|
| `MessageTest` | 9 | Immutable value object, null guards, BROADCAST constant |
| `FixedMessageCountPolicyTest` | 18 | Stop condition logic, boundary cases, parameterised matrix |
| `InitiatorRoleTest` | 11 | Opening message, reply format, stop-signal sequencing |
| `ResponderRoleTest` | 6 | Reply format, never signals stop |
| `PlayerMetricsTest` | 15 | Counters, latency recording, future-timestamp clamping, summary |
| `InMemoryMessageBrokerTest` | 13 | Registration, routing, BROADCAST fan-out, FIFO, backpressure, concurrency |
| `ConversationTest` | 5 | End-to-end 2/3/4-player rings, input validation |
| `SocketChannelTest` | 9 | Wire protocol round-trip: all fields, ordering, Unicode, 100 KB, EOF, bidirectional |

No mocking frameworks are used. All tests exercise real implementations; socket tests use actual loopback TCP connections.

---

## Docker

### Single container (same-process)

```bash
# Build the image
docker build -t player-messaging .

# Run default 2-player conversation
docker run --rm player-messaging

# Run a 4-player ring
docker run --rm player-messaging same-process 4

# Override settings via -e / JAVA_OPTS
docker run --rm -e JAVA_OPTS="-Dmax.messages=20 -Dlog.level=DEBUG" player-messaging same-process

# Mount a custom configuration file (takes precedence over the embedded one)
docker run --rm -v ./my-config.properties:/app/configuration.properties player-messaging same-process
```

### Two containers (multi-process)

**Using Docker Compose (recommended):**

```bash
# First run — build image and start both containers
docker compose up --build

# Subsequent runs — reuse cached image
docker compose up

# Tear down containers and the bridge network
docker compose down
```

Both containers share an isolated bridge network named `messaging-net`. Docker's embedded DNS resolves the service name `responder` so the initiator can connect by name rather than IP.

**Manually (without Compose):**

```bash
# Terminal 1 — responder
docker run --rm --name responder -p 9090:9090 player-messaging responder

# Terminal 2 — initiator (connects to the host's published port)
docker run --rm -e JAVA_OPTS="-Dserver.host=host.docker.internal" player-messaging initiator
```

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                          common package                               │
│                                                                       │
│  Message · MessageBroker · MessageChannel · PlayerRole               │
│  ConversationPolicy · PlayerMetrics · ConfigLoader                   │
│                                                                       │
│  ── Design Pattern interfaces ──────────────────────────────────────  │
│  PlayerComponentFactory   (Abstract Factory)                          │
│  MessageChannelDecorator  (Decorator base)                           │
│  MessageHandler           (Chain of Responsibility)                  │
│  MessageEventListener     (Observer)                                 │
└──────────────────────┬───────────────────────────────────────────────┘
                       │ depends on
         ┌─────────────┴──────────────┐
         ▼                            ▼
┌────────────────────────┐   ┌────────────────────────┐
│      sameprocess       │   │      multiprocess       │
│                        │   │                         │
│  InMemoryMessageBroker │   │  SocketChannel          │
│  DeliveryHandler       │   │  Player                 │
│  Conversation          │   │  MultiProcessMain       │
│  ConversationBuilder   │   │                         │
│  Player                │   │  Decorators applied:    │
│  SameProcessMain       │   │  LoggingChannel         │
│                        │   │  MetricsChannel         │
│  Observer attached:    │   │  wrapping SocketChannel │
│  LoggingEventListener  │   └────────────────────────┘
│  MetricsEventListener  │
│                        │
│  Chain assembled:      │
│  Validation →          │
│  Logging →             │
│  Delivery              │
└────────────────────────┘
```

**Message flow (same-process, 3-player ring):**

```
PlayerA ──► [PlayerB inbox] ──► PlayerB ──► [PlayerC inbox] ──► PlayerC ──► [PlayerA inbox] ──► PlayerA
```

Each player runs on its own Java 21 virtual thread. When the initiator (PlayerA) reaches the message limit it sends a poison-pill stop signal that propagates around the ring until every player has shut down.

**Wire protocol (multi-process):**

```
┌──────────────┬──────────────────────────────────┐
│  4 bytes     │  N bytes (UTF-8)                 │
│  length (N)  │  sender|recipient|epochMs|content │
└──────────────┴──────────────────────────────────┘
```

The 4-byte length prefix makes the protocol framing-safe for arbitrary content (including `|` characters and Unicode), unlike delimiter-based text protocols.

---

## Design Patterns

Five GoF patterns are implemented, each solving a real problem in the codebase.

### Observer — Broker event notifications

**Intent:** Notify interested parties when something happens, without coupling the subject to its observers.

**Where:** `InMemoryMessageBroker` (subject) fires `MessageEvent` after every `publish()` and `receive()`. Any number of `MessageEventListener` implementations can be attached at startup.

**Built-in listeners:**

| Listener | What it does |
|---|---|
| `LoggingEventListener` | Logs every `PUBLISHED` / `DELIVERED` event at DEBUG level |
| `MetricsEventListener` | Maintains atomic aggregate counters across all players |

**Adding a custom listener** (zero changes to broker or player code):
```java
broker.addListener(event -> alertingSystem.check(event));
```

**Output at end of conversation:**
```
[BrokerMetrics] published=22  delivered=21
```

---

### Decorator — Transparent channel enrichment

**Intent:** Add behaviour to an object without modifying it or its callers.

**Where:** `MessageChannelDecorator` (abstract base) wraps any `MessageChannel`. Two concrete decorators are stacked in `MultiProcessMain`:

```
MetricsChannelDecorator
  └── LoggingChannelDecorator
        └── SocketChannel          ← the real transport
```

`Player` only sees `MessageChannel` and is completely unaware of the wrapping. Adding compression or encryption means inserting one more decorator — nothing else changes.

| Decorator | Concern added |
|---|---|
| `LoggingChannelDecorator` | DEBUG log on every send and receive |
| `MetricsChannelDecorator` | Atomic sent/received counters per channel |

---

### Chain of Responsibility — Publish pipeline

**Intent:** Pass a request through a sequence of handlers, each with one focused responsibility.

**Where:** `InMemoryMessageBroker.publish()` runs every message through a chain built at construction time:

```
ValidationHandler  →  LoggingHandler  →  DeliveryHandler
     (guard)            (audit trail)      (enqueue to inbox)
```

| Handler | Responsibility |
|---|---|
| `ValidationHandler` | Rejects messages with blank sender or recipient |
| `LoggingHandler` | Logs the message at TRACE level before forwarding |
| `DeliveryHandler` | Terminal — places the message in the recipient's inbox |

Adding a rate limiter or encryption step means inserting a new `MessageHandler` implementation into the chain — `InMemoryMessageBroker` is never touched.

---

### Abstract Factory — Player component creation

**Intent:** Group creation of related objects behind a single interface so the family can be swapped as a unit.

**Where:** `PlayerComponentFactory` groups `createRole()` + `createPolicy()`. The two concrete factories produce compatible pairs:

| Factory | Role produced | Policy produced |
|---|---|---|
| `InitiatorComponentFactory` | `InitiatorRole` (sends opening message) | `FixedMessageCountPolicy` |
| `ResponderComponentFactory` | `ResponderRole` (echoes messages) | Never-stop lambda |

`Conversation` and `MultiProcessMain` select a factory by index/argument and call `factory.createRole()` — they never reference `InitiatorRole` or `ResponderRole` directly. Swapping the entire behaviour family (e.g. a throttled initiator for load testing) is a one-line factory substitution.

---

### Builder — Fluent Conversation construction

**Intent:** Construct a complex object step by step, making optional parameters explicit and readable.

**Where:** `ConversationBuilder` provides a fluent API for `Conversation`. All parameters default to values from `configuration.properties` so a minimal call works out of the box:

```java
// Minimal — all defaults from configuration.properties
new ConversationBuilder()
    .players("PlayerA", "PlayerB")
    .build()
    .start();

// Fully customised
new ConversationBuilder()
    .players("A", "B", "C", "D")
    .maxMessages(50)
    .openingMessage("Hey!")
    .queueCapacity(32)
    .build()
    .start();
```

Adding a new `Conversation` parameter in the future only requires one new builder method — no existing call sites break.

---

## Design Decisions

| Decision | Rationale |
|---|---|
| **Java 21 virtual threads** | One thread per player costs ~1 KB heap vs ~1 MB for a platform thread, enabling thousands of concurrent players with simple blocking code |
| **`BlockingQueue` backpressure** | A bounded queue (capacity = `inbox.queue.capacity`) causes `publish()` to block when a consumer is slow, preventing unbounded memory growth |
| **Length-prefixed binary protocol** | Eliminates delimiter-injection vulnerabilities and correctly handles Unicode, binary content, and pipe characters in message bodies |
| **Reconnect with exponential back-off** | The initiator retries up to `reconnect.max.attempts` times with doubling delays, tolerating a responder that is still starting up |
| **Externalized configuration** | All tuneable values in `configuration.properties`; `-D` JVM flags override at runtime without rebuilding |
| **SLF4J + Logback** | Standard logging facade; swapping the implementation requires replacing one jar, not editing source code |
| **Multi-stage Dockerfile** | Builder stage compiles and packages; runtime stage ships only the JRE + jars (~tens of MB vs hundreds for a full JDK image) |
| **JUnit 5, no mocking framework** | Tests exercise real implementations; socket tests use actual loopback TCP connections for higher confidence |
| **Observer via `CopyOnWriteArrayList`** | Listeners are registered once at startup (rare writes) but iterated on every publish/receive (frequent reads) — COWAL is optimal for this access pattern |
| **Decorator over inheritance** | Stacking `LoggingChannelDecorator` + `MetricsChannelDecorator` composes two independent concerns without a class-hierarchy explosion |
| **Chain of Responsibility in publish** | Each handler has one responsibility; new publish-time concerns (rate limiting, encryption) are added as new handler classes, not edits to the broker |
| **Abstract Factory for roles** | Role + policy are a product family — keeping their creation together in a factory makes the pairing explicit and swappable as a unit |
| **Builder for Conversation** | Avoids a telescoping constructor as the parameter count grows; all parameters have config-file defaults so the builder is usable with a single `.players()` call |

---

## Future Improvements

The following are deliberate out-of-scope items given the demonstration focus of this project. They represent the natural next steps for a production system.

| Area | Improvement | Why |
|---|---|---|
| **Persistence** | Persist messages to a durable store (e.g. Apache Kafka topic or a relational DB) before delivering to the inbox | Survives process restarts; enables replay and audit trail |
| **Message acknowledgement** | Add `ack()` / `nack()` to `MessageChannel` and retry unacknowledged messages | Guarantees at-least-once delivery; prevents silent message loss on receiver crash |
| **Multi-process N players** | Extend the TCP scenario to support N processes (not just 2) using a central broker or a gossip ring over multiple sockets | Matches the same-process ring topology in the distributed case |
| **TLS transport** | Wrap `SocketChannel` in a `TlsChannelDecorator` (one new `MessageChannelDecorator` subclass) | Encrypts messages in transit without modifying `Player` or any existing code — Decorator pattern pays off here |
| **Rate limiting** | Insert a `RateLimitingHandler` between `LoggingHandler` and `DeliveryHandler` in the Chain of Responsibility | Controls throughput under burst load; adding it requires zero changes to existing handlers |
| **Metrics endpoint** | Expose `MetricsEventListener` counters over HTTP (e.g. Prometheus `/metrics`) | Makes broker throughput observable in production without log scraping |
| **Structured logging** | Switch Logback appender to JSON format (e.g. `logstash-logback-encoder`) | Machine-readable logs that feed directly into ELK / Splunk without parsing |
| **Distributed tracing** | Embed a correlation ID in `Message` and propagate it through the chain | Allows end-to-end request tracing across players and processes |
# multi-player

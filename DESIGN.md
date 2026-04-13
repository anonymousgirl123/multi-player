# Player Messaging — Design Document

This document covers the **High-Level Design (HLD)**, **Low-Level Design (LLD)**, and **flow diagrams** for the Player Messaging system.

---

## Table of Contents

- [High-Level Design (HLD)](#high-level-design-hld)
  - [System Overview](#system-overview)
  - [Scenario 1: Same-Process](#scenario-1-same-process)
  - [Scenario 2: Multi-Process](#scenario-2-multi-process)
  - [Component Responsibilities](#component-responsibilities)
- [Low-Level Design (LLD)](#low-level-design-lld)
  - [Class Diagram — Common Package](#class-diagram--common-package)
  - [Class Diagram — Same-Process Package](#class-diagram--same-process-package)
  - [Class Diagram — Multi-Process Package](#class-diagram--multi-process-package)
  - [Class Diagram — Design Patterns](#class-diagram--design-patterns)
- [Flow Diagrams](#flow-diagrams)
  - [Startup Flow — Same-Process](#startup-flow--same-process)
  - [Startup Flow — Multi-Process](#startup-flow--multi-process)
  - [Message Exchange Loop](#message-exchange-loop)
  - [Publish Pipeline — Chain of Responsibility](#publish-pipeline--chain-of-responsibility)
  - [Shutdown Flow — Poison Pill Propagation](#shutdown-flow--poison-pill-propagation)
  - [Decorator Stack — Multi-Process Channel](#decorator-stack--multi-process-channel)
  - [Observer Notification Flow](#observer-notification-flow)
  - [Wire Protocol — Binary Frame](#wire-protocol--binary-frame)

---

## High-Level Design (HLD)

### System Overview

The system demonstrates peer-to-peer player messaging in two deployment modes that share the same business logic (roles, policies, message format) but differ only in their transport layer.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Player Messaging System                          │
│                                                                          │
│   ┌──────────────────────────────┐  ┌──────────────────────────────┐   │
│   │     Same-Process Mode        │  │     Multi-Process Mode        │   │
│   │                              │  │                               │   │
│   │  All players in one JVM      │  │  Each player in its own JVM  │   │
│   │  Transport: BlockingQueue    │  │  Transport: TCP Socket        │   │
│   │  Threading: Virtual Threads  │  │  Protocol: Binary (4B len)   │   │
│   └──────────────┬───────────────┘  └───────────────┬──────────────┘   │
│                  │                                   │                   │
│          ┌───────┴───────────────────────────────────┘                  │
│          ▼                                                               │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                      Common Abstractions                          │  │
│   │                                                                   │  │
│   │  Message · MessageBroker · MessageChannel · PlayerRole           │  │
│   │  ConversationPolicy · PlayerMetrics · ConfigLoader               │  │
│   │                                                                   │  │
│   │  ── Design Patterns ──────────────────────────────────────────   │  │
│   │  Observer · Decorator · Chain of Responsibility                  │  │
│   │  Abstract Factory · Builder · Strategy · Mediator               │  │
│   └──────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                     Configuration Layer                           │  │
│   │  configuration.properties  ←  ConfigLoader  ←  -D JVM flags     │  │
│   └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

### Scenario 1: Same-Process

All players run as **Java 21 virtual threads** inside a single JVM. They communicate through `InMemoryMessageBroker`, which maintains one bounded `BlockingQueue` per player as its private inbox.

```
┌──────────────────────────────────────────────────────────┐
│                        Single JVM                         │
│                                                           │
│  ┌────────────┐     publish()     ┌────────────────────┐ │
│  │  PlayerA   │ ────────────────► │                    │ │
│  │ (Initiator)│ ◄──────────────── │  InMemoryMessage   │ │
│  │ VThread-1  │     receive()     │      Broker        │ │
│  └────────────┘                   │                    │ │
│                                   │  [PlayerA inbox ●] │ │
│  ┌────────────┐     publish()     │  [PlayerB inbox ●] │ │
│  │  PlayerB   │ ────────────────► │  [PlayerC inbox ●] │ │
│  │ (Responder)│ ◄──────────────── │                    │ │
│  │ VThread-2  │     receive()     │  Observer:         │ │
│  └────────────┘                   │  LoggingListener   │ │
│                                   │  MetricsListener   │ │
│  ┌────────────┐     publish()     │                    │ │
│  │  PlayerC   │ ────────────────► │  Chain:            │ │
│  │ (Responder)│ ◄──────────────── │  Validate → Log →  │ │
│  │ VThread-3  │     receive()     │  Deliver           │ │
│  └────────────┘                   └────────────────────┘ │
│                                                           │
│  Ring: A → B → C → A → B → ...                           │
└──────────────────────────────────────────────────────────┘
```

---

### Scenario 2: Multi-Process

Two JVM processes communicate over **TCP**. The `SocketChannel` uses a 4-byte length-prefixed binary protocol. Each end wraps the channel in a **Decorator** stack for logging and metrics.

```
┌──────────────────────────────┐      TCP       ┌──────────────────────────────┐
│      JVM Process 1            │  port 9090     │       JVM Process 2           │
│      (Responder)              │◄──────────────►│       (Initiator)             │
│                               │                │                               │
│  ┌─────────────────────────┐  │                │  ┌─────────────────────────┐  │
│  │  MultiProcessMain       │  │                │  │  MultiProcessMain       │  │
│  │  ServerSocket.accept()  │  │                │  │  connectWithRetry()     │  │
│  └──────────┬──────────────┘  │                │  └──────────┬──────────────┘  │
│             │                 │                │             │                  │
│  ┌──────────▼──────────────┐  │                │  ┌──────────▼──────────────┐  │
│  │  MetricsChannel         │  │  [4B][body]    │  │  MetricsChannel         │  │
│  │  └ LoggingChannel       │──┼────────────────┼──┤  └ LoggingChannel       │  │
│  │    └ SocketChannel      │  │                │  │    └ SocketChannel      │  │
│  └──────────┬──────────────┘  │                │  └──────────┬──────────────┘  │
│             │                 │                │             │                  │
│  ┌──────────▼──────────────┐  │                │  ┌──────────▼──────────────┐  │
│  │  Player (Responder)     │  │                │  │  Player (Initiator)     │  │
│  │  ResponderRole          │  │                │  │  InitiatorRole          │  │
│  │  PlayerMetrics          │  │                │  │  FixedMsgCountPolicy    │  │
│  └─────────────────────────┘  │                │  └─────────────────────────┘  │
└──────────────────────────────┘                └──────────────────────────────┘
```

---

### Component Responsibilities

| Component | Package | Responsibility |
|---|---|---|
| `SameProcessMain` | `sameprocess` | Entry point — parses player count, delegates to `ConversationBuilder` |
| `ConversationBuilder` | `sameprocess` | **Builder** — fluent construction of `Conversation` with defaults |
| `Conversation` | `sameprocess` | Wires N-player ring, manages virtual thread pool, attaches Observer listeners |
| `InMemoryMessageBroker` | `sameprocess` | **Mediator + Observer Subject + Chain host** — routes messages, fires events |
| `DeliveryHandler` | `sameprocess` | **Chain** terminal — places message into recipient's `BlockingQueue` |
| `sameprocess.Player` | `sameprocess` | Role-agnostic message loop over `MessageBroker` |
| `MultiProcessMain` | `multiprocess` | Entry point — TCP wiring, reconnect, Decorator stack assembly |
| `SocketChannel` | `multiprocess` | Binary wire protocol — length-prefixed frames over TCP |
| `multiprocess.Player` | `multiprocess` | Role-agnostic message loop over `MessageChannel` |
| `Message` | `common` | Immutable value object — sender, recipient, content, sentAt |
| `MessageBroker` | `common` | Mediator interface — register, publish, receive, shutdown, addListener |
| `MessageChannel` | `common` | Transport abstraction — send, receive, close |
| `PlayerRole` | `common` | **Strategy** interface — onConversationStart, onMessageReceived |
| `InitiatorRole` | `common` | Sends opening message; applies stop policy |
| `ResponderRole` | `common` | Echoes messages; never stops on its own |
| `ConversationPolicy` | `common` | **Strategy** — shouldStop(sent, received) |
| `FixedMessageCountPolicy` | `common` | Stops when both sent ≥ max AND received ≥ max |
| `PlayerComponentFactory` | `common` | **Abstract Factory** interface — createRole + createPolicy |
| `InitiatorComponentFactory` | `common` | Produces InitiatorRole + FixedMessageCountPolicy |
| `ResponderComponentFactory` | `common` | Produces ResponderRole + never-stop policy |
| `MessageChannelDecorator` | `common` | **Decorator** abstract base — delegates all three channel methods |
| `LoggingChannelDecorator` | `common` | **Decorator** — DEBUG logs every send and receive |
| `MetricsChannelDecorator` | `common` | **Decorator** — atomic sent/received counters |
| `MessageHandler` | `common` | **Chain of Responsibility** handler interface |
| `ValidationHandler` | `common` | Rejects messages with blank sender or recipient |
| `LoggingHandler` | `common` | TRACE logs message before forwarding |
| `MessageEvent` | `common` | Immutable event payload for Observer notifications |
| `MessageEventListener` | `common` | **Observer** subscriber interface |
| `LoggingEventListener` | `common` | DEBUG logs PUBLISHED/DELIVERED broker events |
| `MetricsEventListener` | `common` | Atomic aggregate published/delivered counters |
| `PlayerMetrics` | `common` | Per-player sent/received counts and one-way latency |
| `ConfigLoader` | `common` | Loads `configuration.properties`; promotes to System properties |

---


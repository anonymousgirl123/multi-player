package com.playermessaging.player.common.broker;

import com.playermessaging.player.common.model.MessageEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MessageEventListener} that logs every broker event at DEBUG level.
 *
 * <h3>Observer Pattern — Concrete Observer</h3>
 *
 * <p>Attach this listener to a {@link MessageBroker} to get a broker-level audit trail of every
 * {@code PUBLISHED} and {@code DELIVERED} event without modifying any player or broker code.
 *
 * <p>The log output looks like:
 *
 * <pre>
 *   [BrokerEvent] PUBLISHED  [PlayerA → PlayerB @ 2024-...] Hello
 *   [BrokerEvent] DELIVERED  [PlayerA → PlayerB @ 2024-...] Hello
 * </pre>
 *
 * <p>Because this class only logs, it is inherently thread-safe (SLF4J loggers are thread-safe by
 * contract).
 */
public final class LoggingEventListener implements MessageEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventListener.class);

    @Override
    public void onEvent(MessageEvent event) {
        log.debug("[BrokerEvent] {}\t{}", event.getKind(), event.getMessage());
    }
}

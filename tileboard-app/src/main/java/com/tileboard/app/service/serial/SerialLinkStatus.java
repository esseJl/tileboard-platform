package com.tileboard.app.service.serial;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable, point-in-time answer to "is the serial link really usable right now?".
 *
 * <p>{@code inPort}/{@code outPort} are the ports of the <em>live session</em> (what was
 * actually opened at connect time), NOT the persisted assignment - the two can differ
 * when the operator re-assigns a port while connected.
 *
 * @param state          live-verified state; {@code DISCONNECTED} whenever the link is lost
 * @param condition      finer-grained verdict, see {@link LinkCondition}
 * @param inPort         input port of the live session, or {@code null}
 * @param outPort        output port of the live session, or {@code null}
 * @param missingPorts   session ports that are no longer visible on the host (empty when none)
 * @param connectedSince when the live session was established, or {@code null}
 * @param checkedAt      when this snapshot was produced
 * @param detail         human-readable (English) diagnostic, or {@code null}
 */
public record SerialLinkStatus(
        ConnectionState state,
        LinkCondition condition,
        String inPort,
        String outPort,
        Set<String> missingPorts,
        Instant connectedSince,
        Instant checkedAt,
        String detail) {

    public SerialLinkStatus {
        missingPorts = missingPorts == null ? Set.of() : Set.copyOf(missingPorts);
    }

    public static SerialLinkStatus notConnected(Instant checkedAt) {
        return new SerialLinkStatus(ConnectionState.DISCONNECTED, LinkCondition.NOT_CONNECTED,
                null, null, Set.of(), null, checkedAt, "No gateway session is open.");
    }
}

package com.tileboard.app.service.serial;

/**
 * The <em>verified</em> condition of the serial link, as opposed to
 * {@link ConnectionState} which is only the coarse connected/disconnected answer.
 */
public enum LinkCondition {

    /** No gateway session exists (never connected, or the operator disconnected). */
    NOT_CONNECTED,

    /** A session exists and every port it uses is still present on the host. */
    HEALTHY,

    /**
     * A session exists in memory, but at least one of its ports has disappeared from
     * the host (USB adapter unplugged, device powered off and re-enumerated, ...).
     * The in-memory session is dead even though nothing told us so.
     */
    LINK_LOST,

    /** A session exists, but the host's port list could not be read, so nothing can be claimed. */
    UNVERIFIED
}

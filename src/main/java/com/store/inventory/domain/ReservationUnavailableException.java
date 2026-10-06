package com.store.inventory.domain;

/** Expected confirmation rejection, distinct from an internal consistency failure. */
public final class ReservationUnavailableException extends IllegalStateException {
    public ReservationUnavailableException(String orderId) {
        super("No active reservation for " + orderId);
    }
}

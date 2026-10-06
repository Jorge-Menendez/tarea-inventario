package com.store.inventory.domain;

import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.Objects;

public final class ReservationEntry {
    private enum State { ACTIVE, CONFIRMED, EXPIRED }
    private final Reservation reservation;
    private State state = State.ACTIVE;

    public ReservationEntry(Reservation reservation) {
        this.reservation = Objects.requireNonNull(reservation, "reservation");
    }

    public Reservation reservation() {
        return reservation;
    }

    public Instant expiresAt() {
        return reservation.expiresAt();
    }

    public String status() {
        return state.name();
    }

    public Reservation retry(String sku, int quantity) {
        if (!reservation.sku().equals(sku) || reservation.quantity() != quantity) {
            throw new IllegalArgumentException("Order id reused with different details: " + reservation.orderId());
        }
        return reservation;
    }

    public boolean expire(Instant now) {
        if (state == State.ACTIVE && !now.isBefore(expiresAt())) {
            state = State.EXPIRED;
            return true;
        }
        return false;
    }

    public boolean confirm() {
        if (state == State.EXPIRED) {
            throw new ReservationUnavailableException(reservation.orderId());
        }
        if (state == State.CONFIRMED) {
            return false;
        }
        state = State.CONFIRMED;
        return true;
    }
}

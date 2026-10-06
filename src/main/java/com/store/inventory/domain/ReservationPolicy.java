package com.store.inventory.domain;

import com.store.inventory.api.OrderLimitExceededException;
import java.time.Duration;
import java.util.Objects;

public record ReservationPolicy(Duration lifetime, int orderLimit) {
    public ReservationPolicy {
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isNegative() || lifetime.isZero() || orderLimit <= 0) {
            throw new IllegalArgumentException("Lifetime and order limit must be positive");
        }
    }

    public void validateQuantity(String sku, int quantity) {
        if (quantity > orderLimit) {
            throw new OrderLimitExceededException(sku, quantity, orderLimit);
        }
    }
}

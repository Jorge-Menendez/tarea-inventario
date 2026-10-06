package com.store.inventory.application;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.application.port.InventorySession;
import com.store.inventory.application.port.InventoryStore;
import com.store.inventory.application.port.ReservationPolicies;
import com.store.inventory.domain.ProductStock;
import com.store.inventory.domain.ReservationEntry;
import com.store.inventory.domain.ReservationPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Coordinates use cases; persistence and category rules are injected ports. */
public final class DefaultInventoryService implements InventoryService {
    private static final System.Logger LOG = System.getLogger(DefaultInventoryService.class.getName());
    private final Clock clock;
    private final StockAlertListener listener;
    private final InventoryStore store;
    private final ReservationPolicies policies;

    public DefaultInventoryService(Clock clock, StockAlertListener listener,
                                   InventoryStore store, ReservationPolicies policies) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "alertListener");
        this.store = Objects.requireNonNull(store, "store");
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireIdentifier(sku, "sku");
        Objects.requireNonNull(category, "category");
        policies.forCategory(category);
        store.executeExclusive(session -> {
            Optional<ProductStock> existing = session.findProduct(sku);
            if (existing.isPresent()) {
                if (existing.get().category() != category) {
                    throw new IllegalArgumentException("Product already has another category: " + sku);
                }
            } else {
                session.addProduct(sku, new ProductStock(category));
            }
            return null;
        });
    }

    @Override
    public void addStock(String sku, int quantity) {
        requireIdentifier(sku, "sku");
        requirePositiveQuantity(quantity);
        Optional<LowStockAlert> alert = store.executeExclusive(session -> {
            ProductStock product = session.findProduct(sku)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown product: " + sku));
            expireReservations(session, clock.instant());
            product.replenish(quantity);
            return claimAlert(sku, product);
        });
        alert.ifPresent(this::notifyAlert);
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireIdentifier(orderId, "orderId");
        requireIdentifier(sku, "sku");
        requirePositiveQuantity(quantity);
        ReservationResult result = store.executeExclusive(session -> {
            Instant now = clock.instant();
            expireReservations(session, now);
            Optional<ReservationEntry> existing = session.findOrder(orderId);
            if (existing.isPresent()) {
                return new ReservationResult(existing.get().retry(sku, quantity), Optional.empty());
            }
            ProductStock product = session.findProduct(sku)
                    .orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));
            ReservationPolicy policy = policies.forCategory(product.category());
            policy.validateQuantity(sku, quantity);
            Reservation reservation = new Reservation(orderId, sku, quantity, now.plus(policy.lifetime()));
            product.reserve(sku, quantity);
            session.addOrder(new ReservationEntry(reservation));
            return new ReservationResult(reservation, claimAlert(sku, product));
        });
        result.alert().ifPresent(this::notifyAlert);
        return result.reservation();
    }

    @Override
    public void confirm(String orderId) {
        requireIdentifier(orderId, "orderId");
        store.executeExclusive(session -> {
            expireReservations(session, clock.instant());
            ReservationEntry order = session.findOrder(orderId)
                    .orElseThrow(() -> new IllegalStateException("No active reservation for " + orderId));
            if (order.confirm()) {
                Reservation reservation = order.reservation();
                requiredProduct(session, reservation.sku()).sell(reservation.quantity());
            }
            return null;
        });
    }

    @Override
    public int available(String sku) {
        requireIdentifier(sku, "sku");
        return store.executeExclusive(session -> {
            expireReservations(session, clock.instant());
            return session.findProduct(sku).map(ProductStock::availableUnits).orElse(0);
        });
    }

    private void expireReservations(InventorySession session, Instant now) {
        for (ReservationEntry order : session.removeDueReservations(now)) {
            if (order.expire(now)) {
                Reservation reservation = order.reservation();
                requiredProduct(session, reservation.sku()).release(reservation.quantity());
            }
        }
    }

    private static ProductStock requiredProduct(InventorySession session, String sku) {
        return session.findProduct(sku)
                .orElseThrow(() -> new IllegalStateException("Reservation references missing product: " + sku));
    }

    private static Optional<LowStockAlert> claimAlert(String sku, ProductStock product) {
        var units = product.claimLowStockAlert();
        return units.isPresent() ? Optional.of(new LowStockAlert(sku, units.getAsInt())) : Optional.empty();
    }

    private void notifyAlert(LowStockAlert alert) {
        // External code must run after the exclusive operation has finished.
        try {
            listener.onLowStock(alert.sku(), alert.availableUnits());
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "Low stock notification failed for " + alert.sku(), failure);
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }

    private record LowStockAlert(String sku, int availableUnits) { }
    private record ReservationResult(Reservation reservation, Optional<LowStockAlert> alert) { }
}

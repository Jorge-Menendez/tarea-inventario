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
import com.store.inventory.domain.ReservationUnavailableException;
import com.store.inventory.application.observability.LogValues;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates use cases; persistence and category rules are injected ports. */
public final class DefaultInventoryService implements InventoryService {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultInventoryService.class);
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
                LOG.info("event=product_registration_replayed sku={} category={}", LogValues.safe(sku), category);
            } else {
                session.addProduct(sku, new ProductStock(category));
                LOG.info("event=product_registered sku={} category={}", LogValues.safe(sku), category);
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
            int before = product.availableUnits();
            product.replenish(quantity);
            LOG.info("event=stock_replenished sku={} quantity={} availableBefore={} availableAfter={}",
                    LogValues.safe(sku), quantity, before, product.availableUnits());
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
                ReservationEntry order = existing.get();
                Reservation reservation = order.retry(sku, quantity);
                LOG.info("event=reservation_replayed orderId={} sku={} quantity={} status={} expiresAt={}",
                        LogValues.safe(orderId), LogValues.safe(sku), quantity, order.status(), reservation.expiresAt());
                return new ReservationResult(reservation, Optional.empty());
            }
            ProductStock product = session.findProduct(sku)
                    .orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));
            ReservationPolicy policy = policies.forCategory(product.category());
            LOG.debug("event=reservation_policy_resolved category={} lifetime={} orderLimit={}",
                    product.category(), policy.lifetime(), policy.orderLimit());
            policy.validateQuantity(sku, quantity);
            Reservation reservation = new Reservation(orderId, sku, quantity, now.plus(policy.lifetime()));
            int before = product.availableUnits();
            product.reserve(sku, quantity);
            session.addOrder(new ReservationEntry(reservation));
            LOG.info("event=reservation_created orderId={} sku={} quantity={} expiresAt={} availableBefore={} availableAfter={}",
                    LogValues.safe(orderId), LogValues.safe(sku), quantity, reservation.expiresAt(),
                    before, product.availableUnits());
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
                    .orElseThrow(() -> new ReservationUnavailableException(orderId));
            Reservation reservation = order.reservation();
            if (order.confirm()) {
                ProductStock product = requiredProduct(session, reservation.sku());
                product.sell(reservation.quantity());
                LOG.info("event=reservation_confirmed orderId={} sku={} quantity={} availableAfter={}",
                        LogValues.safe(orderId), LogValues.safe(reservation.sku()),
                        reservation.quantity(), product.availableUnits());
            } else {
                LOG.info("event=confirmation_replayed orderId={} sku={} quantity={} status={}",
                        LogValues.safe(orderId), LogValues.safe(reservation.sku()),
                        reservation.quantity(), order.status());
            }
            return null;
        });
    }

    @Override
    public int available(String sku) {
        requireIdentifier(sku, "sku");
        return store.executeExclusive(session -> {
            expireReservations(session, clock.instant());
            Optional<ProductStock> product = session.findProduct(sku);
            int units = product.map(ProductStock::availableUnits).orElse(0);
            LOG.debug("event=availability_read sku={} productRegistered={} availableUnits={}",
                    LogValues.safe(sku), product.isPresent(), units);
            return units;
        });
    }

    private void expireReservations(InventorySession session, Instant now) {
        for (ReservationEntry order : session.removeDueReservations(now)) {
            if (order.expire(now)) {
                Reservation reservation = order.reservation();
                ProductStock product = requiredProduct(session, reservation.sku());
                int before = product.availableUnits();
                product.release(reservation.quantity());
                LOG.info("event=reservation_expired expiredOrderId={} expiredSku={} quantity={} expiresAt={} processedAt={} availableBefore={} availableAfter={}",
                        LogValues.safe(reservation.orderId()), LogValues.safe(reservation.sku()),
                        reservation.quantity(), reservation.expiresAt(), now, before, product.availableUnits());
            } else {
                LOG.debug("event=expiration_entry_discarded orderId={} status={}",
                        LogValues.safe(order.reservation().orderId()), order.status());
            }
        }
    }

    private static ProductStock requiredProduct(InventorySession session, String sku) {
        return session.findProduct(sku)
                .orElseThrow(() -> new IllegalStateException("Reservation references missing product: " + sku));
    }

    private static Optional<LowStockAlert> claimAlert(String sku, ProductStock product) {
        var units = product.claimLowStockAlert();
        if (units.isPresent()) {
            LOG.info("event=low_stock_alert_claimed sku={} availableUnits={}", LogValues.safe(sku), units.getAsInt());
            return Optional.of(new LowStockAlert(sku, units.getAsInt()));
        }
        LOG.debug("event=low_stock_alert_not_required sku={} availableUnits={} reason=above_threshold_or_already_reported",
                LogValues.safe(sku), product.availableUnits());
        return Optional.empty();
    }

    private void notifyAlert(LowStockAlert alert) {
        // External code must run after the exclusive operation has finished.
        long started = System.nanoTime();
        LOG.info("event=notification_started sku={} availableUnits={}", LogValues.safe(alert.sku()), alert.availableUnits());
        try {
            listener.onLowStock(alert.sku(), alert.availableUnits());
            LOG.info("event=notification_completed outcome=success sku={} durationMicros={}",
                    LogValues.safe(alert.sku()), TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started));
        } catch (RuntimeException failure) {
            LOG.warn("event=notification_failed outcome=failure inventoryChangePreserved=true retryScheduled=false sku={} durationMicros={}",
                    LogValues.safe(alert.sku()), TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started), failure);
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

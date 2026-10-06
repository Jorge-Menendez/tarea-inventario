package com.store.inventory.infrastructure.memory;

import com.store.inventory.application.port.InventorySession;
import com.store.inventory.application.port.InventoryStore;
import com.store.inventory.domain.ProductStock;
import com.store.inventory.domain.ReservationEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InMemoryInventoryStore implements InventoryStore {
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryInventoryStore.class);
    private final Object lock = new Object();
    private final Session session = new Session();

    @Override
    public <T> T executeExclusive(Function<InventorySession, T> operation) {
        long started = System.nanoTime();
        long acquired = 0;
        long finished = 0;
        try {
            synchronized (lock) {
                acquired = System.nanoTime();
                try {
                    return operation.apply(session);
                } finally {
                    finished = System.nanoTime();
                }
            }
        } finally {
            if (acquired != 0) {
                LOG.debug("event=store_operation_finished waitMicros={} exclusiveMicros={}",
                        TimeUnit.NANOSECONDS.toMicros(acquired - started),
                        TimeUnit.NANOSECONDS.toMicros(finished - acquired));
            }
        }
    }

    private static final class Session implements InventorySession {
        private final Map<String, ProductStock> products = new HashMap<>();
        private final Map<String, ReservationEntry> orders = new HashMap<>();
        private final PriorityQueue<ReservationEntry> deadlines =
                new PriorityQueue<>(Comparator.comparing(ReservationEntry::expiresAt));

        @Override
        public Optional<ProductStock> findProduct(String sku) {
            return Optional.ofNullable(products.get(sku));
        }

        @Override
        public void addProduct(String sku, ProductStock product) {
            if (products.putIfAbsent(sku, product) != null) {
                throw new IllegalStateException("Duplicate product: " + sku);
            }
        }

        @Override
        public Optional<ReservationEntry> findOrder(String orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        public void addOrder(ReservationEntry order) {
            String orderId = order.reservation().orderId();
            if (orders.putIfAbsent(orderId, order) != null) {
                throw new IllegalStateException("Duplicate order: " + orderId);
            }
            deadlines.add(order);
        }

        @Override
        public List<ReservationEntry> removeDueReservations(Instant now) {
            List<ReservationEntry> due = new ArrayList<>();
            while (!deadlines.isEmpty() && !now.isBefore(deadlines.peek().expiresAt())) {
                due.add(deadlines.remove());
            }
            return due;
        }
    }
}

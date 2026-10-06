package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.application.CategoryPolicies;
import com.store.inventory.application.DefaultInventoryService;
import com.store.inventory.domain.ReservationPolicy;
import com.store.inventory.infrastructure.memory.InMemoryInventoryStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryBehaviorTest {
    private final MutableClock clock = new MutableClock();
    private final List<Integer> alerts = new CopyOnWriteArrayList<>();
    private final InventoryService service = Inventory.create(clock, (sku, count) -> alerts.add(count));

    private void stock(ProductCategory category, int quantity) {
        service.registerProduct("sku", category);
        service.addStock("sku", quantity);
    }

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void categoryDeadlineAndExactExpiration(ProductCategory category) {
        Duration lifetime = switch (category) {
            case STANDARD -> Duration.ofMinutes(15);
            case PRE_ORDER -> Duration.ofHours(24);
            case FLASH_SALE -> Duration.ofMinutes(5);
        };
        stock(category, 10);
        Instant start = clock.instant();
        Reservation reservation = service.reserve("order", "sku", 2);
        assertEquals(start.plus(lifetime), reservation.expiresAt());

        clock.set(reservation.expiresAt().minusNanos(1));
        assertEquals(8, service.available("sku"));
        clock.set(reservation.expiresAt());
        assertEquals(10, service.available("sku"));
        assertThrows(IllegalStateException.class, () -> service.confirm("order"));
        assertSame(reservation, service.reserve("order", "sku", 2));
        assertEquals(10, service.available("sku"));
    }

    @Test
    void retriesAndConfirmationDoNotDeductTwice() {
        stock(ProductCategory.STANDARD, 10);
        Reservation original = service.reserve("order", "sku", 3);
        clock.advance(Duration.ofMinutes(1));
        assertSame(original, service.reserve("order", "sku", 3));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("order", "sku", 4));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("order", "another", 3));
        service.confirm("order");
        service.confirm("order");
        assertSame(original, service.reserve("order", "sku", 3));
        clock.advance(Duration.ofDays(2));
        assertEquals(7, service.available("sku"));
    }

    @Test
    void flashSaleEnforcesLimitWithoutChangingStock() {
        stock(ProductCategory.FLASH_SALE, 10);
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("order", "sku", 3));
        assertEquals(10, service.available("sku"));
        service.reserve("order", "sku", 2);
        assertEquals(8, service.available("sku"));
    }

    @Test
    void alertsRemainSuppressedUntilReplenishment() {
        stock(ProductCategory.STANDARD, 10);
        service.reserve("one", "sku", 5);
        service.reserve("two", "sku", 1);
        assertEquals(List.of(5), alerts);
        clock.advance(Duration.ofMinutes(15));
        assertEquals(10, service.available("sku"));
        service.reserve("three", "sku", 8);
        assertEquals(List.of(5), alerts);
        service.addStock("sku", 1);
        assertEquals(List.of(5, 3), alerts);
        service.addStock("sku", 10);
        service.reserve("four", "sku", 8);
        assertEquals(List.of(5, 3, 5), alerts);
    }

    @Test
    void validatesInputsAndPreservesRegisteredStock() {
        assertEquals(0, service.available("unknown"));
        assertThrows(IllegalArgumentException.class, () -> service.addStock("unknown", 1));
        assertThrows(InsufficientStockException.class, () -> service.reserve("order", "unknown", 1));
        stock(ProductCategory.STANDARD, 10);
        service.registerProduct("sku", ProductCategory.STANDARD);
        assertThrows(IllegalArgumentException.class,
                () -> service.registerProduct("sku", ProductCategory.FLASH_SALE));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("order", "sku", 0));
        assertThrows(IllegalArgumentException.class, () -> service.addStock("sku", -1));
        assertThrows(IllegalArgumentException.class, () -> service.reserve(" ", "sku", 1));
        assertThrows(IllegalArgumentException.class, () -> service.available(null));
        assertThrows(IllegalStateException.class, () -> service.confirm("unknown"));
        assertEquals(10, service.available("sku"));
    }

    @Test
    void overflowAccountsForHeldUnits() {
        stock(ProductCategory.STANDARD, Integer.MAX_VALUE);
        service.reserve("order", "sku", 10);
        assertThrows(ArithmeticException.class, () -> service.addStock("sku", 1));
        clock.advance(Duration.ofMinutes(15));
        assertEquals(Integer.MAX_VALUE, service.available("sku"));
    }

    @Test
    void confirmedUnitsAllowFurtherReplenishment() {
        stock(ProductCategory.STANDARD, Integer.MAX_VALUE);
        service.reserve("order", "sku", 10);
        service.confirm("order");
        service.confirm("order");
        service.addStock("sku", 10);
        assertEquals(Integer.MAX_VALUE, service.available("sku"));
        assertThrows(ArithmeticException.class, () -> service.addStock("sku", 1));
    }

    @Test
    void listenerFailureDoesNotUndoPurchase() {
        AtomicInteger notifications = new AtomicInteger();
        InventoryService inventory = Inventory.create(clock, (sku, count) -> {
            notifications.incrementAndGet();
            throw new IllegalStateException("mail unavailable");
        });
        inventory.registerProduct("sku", ProductCategory.STANDARD);
        inventory.addStock("sku", 10);
        Reservation original = assertDoesNotThrow(() -> inventory.reserve("order", "sku", 5));
        assertEquals(5, inventory.available("sku"));
        assertSame(original, inventory.reserve("order", "sku", 5));
        assertEquals(1, notifications.get());
    }

    @Test
    void concurrentOrdersCannotOversell() throws Exception {
        stock(ProductCategory.STANDARD, 20);
        AtomicInteger successes = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String orderId = "order-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.reserve(orderId, "sku", 1);
                        successes.incrementAndGet();
                    } catch (InsufficientStockException expected) {
                        // Requests beyond the initial stock must fail.
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(20, successes.get());
        assertEquals(0, service.available("sku"));
        assertEquals(List.of(5), alerts);
    }

    @Test
    void concurrentRetriesShareOneReservation() throws Exception {
        stock(ProductCategory.STANDARD, 10);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Reservation> request = () -> {
            start.await();
            return service.reserve("order", "sku", 2);
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<Reservation>> results = new ArrayList<>();
            for (Callable<Reservation> task : Collections.nCopies(40, request)) {
                results.add(executor.submit(task));
            }
            start.countDown();
            Reservation original = results.getFirst().get(10, TimeUnit.SECONDS);
            for (Future<Reservation> result : results) {
                assertSame(original, result.get(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(8, service.available("sku"));
    }

    @Test
    void expirationIsOrderedByDeadlineAcrossCategories() {
        service.registerProduct("long", ProductCategory.PRE_ORDER);
        service.registerProduct("short", ProductCategory.FLASH_SALE);
        service.addStock("long", 10);
        service.addStock("short", 10);
        service.reserve("long-order", "long", 3);
        service.reserve("short-order", "short", 2);
        clock.advance(Duration.ofMinutes(5));
        assertEquals(10, service.available("short"));
        assertEquals(7, service.available("long"));
        service.confirm("long-order");
        clock.advance(Duration.ofDays(1));
        assertEquals(7, service.available("long"));
    }

    @Test
    void confirmationAndExpirationRaceCannotSellExpiredUnits() throws Exception {
        stock(ProductCategory.STANDARD, 10);
        Reservation reservation = service.reserve("order", "sku", 3);
        clock.set(reservation.expiresAt());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> confirmation = executor.submit(() -> {
                start.await();
                assertThrows(IllegalStateException.class, () -> service.confirm("order"));
                return null;
            });
            Future<Integer> availability = executor.submit(() -> {
                start.await();
                return service.available("sku");
            });
            start.countDown();
            confirmation.get(10, TimeUnit.SECONDS);
            assertEquals(10, availability.get(10, TimeUnit.SECONDS));
        }
        assertEquals(10, service.available("sku"));
    }

    @Test
    void listenerAllowsQueriesFromAnotherThread() {
        AtomicReference<InventoryService> reference = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            InventoryService inventory = Inventory.create(clock, (sku, count) -> {
                Future<Integer> query = executor.submit(() -> reference.get().available(sku));
                try {
                    assertEquals(count, query.get(2, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError("Listener must execute outside inventory lock", failure);
                }
            });
            reference.set(inventory);
            inventory.registerProduct("sku", ProductCategory.STANDARD);
            inventory.addStock("sku", 10);
            inventory.reserve("order", "sku", 5);
        }
    }

    @Test
    void categoryRulesCanBeReplacedWithoutChangingService() {
        CategoryPolicies customPolicies = new CategoryPolicies(Map.of(
                ProductCategory.STANDARD, new ReservationPolicy(Duration.ofSeconds(30), 1)));
        InventoryService inventory = new DefaultInventoryService(clock, (sku, count) -> {},
                new InMemoryInventoryStore(), customPolicies);
        inventory.registerProduct("sku", ProductCategory.STANDARD);
        inventory.addStock("sku", 10);
        assertThrows(OrderLimitExceededException.class, () -> inventory.reserve("large", "sku", 2));
        Reservation reservation = inventory.reserve("order", "sku", 1);
        assertEquals(clock.instant().plusSeconds(30), reservation.expiresAt());
        clock.advance(Duration.ofSeconds(30));
        assertEquals(10, inventory.available("sku"));
    }

    @Test
    void instancesKeepIndependentInventories() {
        stock(ProductCategory.STANDARD, 10);
        InventoryService another = Inventory.create(clock, (sku, count) -> {});
        assertEquals(0, another.available("sku"));
        assertEquals(10, service.available("sku"));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now.get(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}

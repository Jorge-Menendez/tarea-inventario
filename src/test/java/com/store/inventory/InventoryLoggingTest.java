package com.store.inventory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.application.CategoryPolicies;
import com.store.inventory.application.DefaultInventoryService;
import com.store.inventory.application.observability.LoggingInventoryService;
import com.store.inventory.application.port.InventorySession;
import com.store.inventory.application.port.InventoryStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryLoggingTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.store.inventory");
    private final CapturingAppender capture = new CapturingAppender();
    private Level previousLevel;
    private Map<String, String> previousContext;
    private final AdjustableClock clock = new AdjustableClock();

    @BeforeEach
    void captureEvents() {
        previousContext = MDC.getCopyOfContextMap();
        MDC.clear();
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        capture.setContext(logger.getLoggerContext());
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void restoreLogging() {
        logger.detachAppender(capture);
        capture.stop();
        logger.setLevel(previousLevel);
        if (previousContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }

    @Test
    void operationLinksBusinessEventsAndRestoresCallerContext() {
        InventoryService service = stockedService((sku, units) -> {});
        Map<String, String> caller = Map.of("traceId", "external-trace", "tenant", "store", "quantity", "old");
        MDC.setContextMap(caller);
        capture.events.clear();

        service.reserve("order", "sku", 5);

        ILoggingEvent started = first("operation_started");
        String operationId = started.getMDCPropertyMap().get("operationId");
        assertNotNull(operationId);
        for (ILoggingEvent event : capture.events) {
            assertEquals("external-trace", event.getMDCPropertyMap().get("traceId"));
            assertEquals(operationId, event.getMDCPropertyMap().get("operationId"));
            assertEquals("order", event.getMDCPropertyMap().get("orderId"));
            assertEquals("5", event.getMDCPropertyMap().get("quantity"));
        }
        assertTrue(first("reservation_created").getFormattedMessage().contains("availableBefore=10 availableAfter=5"));
        assertTrue(first("operation_completed").getFormattedMessage().contains("durationMicros="));
        assertNotNull(first("notification_completed"));
        assertEquals(caller, MDC.getCopyOfContextMap());
    }

    @Test
    void replayEventsDoNotReportDuplicateSales() {
        InventoryService service = stockedService((sku, units) -> {});
        capture.events.clear();
        service.reserve("order", "sku", 2);
        service.reserve("order", "sku", 2);
        service.confirm("order");
        service.confirm("order");
        service.reserve("order", "sku", 2);

        assertEquals(1, events("reservation_created").size());
        assertEquals(1, events("reservation_confirmed").size());
        assertEquals(2, events("reservation_replayed").size());
        assertEquals(1, events("confirmation_replayed").size());
        assertTrue(events("reservation_replayed").getLast().getFormattedMessage().contains("status=CONFIRMED"));
        assertNull(MDC.getCopyOfContextMap());
    }

    @Test
    void expiredOrderIsIdentifiedSeparatelyFromTriggeringOperation() {
        InventoryService service = stockedService((sku, units) -> {});
        Reservation reservation = service.reserve("expired-order", "sku", 2);
        clock.now = reservation.expiresAt();
        capture.events.clear();

        assertEquals(0, service.available("another-sku"));

        ILoggingEvent expired = first("reservation_expired");
        assertTrue(expired.getFormattedMessage().contains("expiredOrderId=expired-order expiredSku=sku"));
        assertEquals("available", expired.getMDCPropertyMap().get("operation"));
        assertEquals("another-sku", expired.getMDCPropertyMap().get("sku"));
        assertFalse(expired.getMDCPropertyMap().containsKey("orderId"));
        assertTrue(expired.getFormattedMessage().contains("availableBefore=8 availableAfter=10"));
    }

    @Test
    void businessRejectionsHaveWarnLevelAndKeepTheOriginalException() {
        InventoryService service = stockedService((sku, units) -> {});
        capture.events.clear();
        assertThrows(InsufficientStockException.class, () -> service.reserve("order", "sku", 11));
        assertThrows(IllegalStateException.class, () -> service.confirm("missing"));

        for (ILoggingEvent rejection : events("operation_rejected")) {
            assertEquals(Level.WARN, rejection.getLevel());
            assertNull(rejection.getThrowableProxy());
        }
        assertEquals(2, events("operation_rejected").size());
        assertEquals(0, events("operation_failed").size());
        assertEquals(0, events("operation_completed").size());
        assertNull(MDC.getCopyOfContextMap());
        assertEquals(10, service.available("sku"));
    }

    @Test
    void unexpectedFailureHasErrorLevelAndStackTrace() {
        IllegalStateException failure = new IllegalStateException("storage inconsistent");
        InventoryStore brokenStore = new InventoryStore() {
            @Override
            public <T> T executeExclusive(Function<InventorySession, T> operation) {
                throw failure;
            }
        };
        InventoryService service = new LoggingInventoryService(new DefaultInventoryService(
                clock, (sku, units) -> {}, brokenStore, CategoryPolicies.defaults()));
        MDC.put("traceId", "failure-trace");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.available("sku")));

        ILoggingEvent event = first("operation_failed");
        assertEquals(Level.ERROR, event.getLevel());
        assertEquals(failure.getClass().getName(), event.getThrowableProxy().getClassName());
        assertEquals(Map.of("traceId", "failure-trace"), MDC.getCopyOfContextMap());
    }

    @Test
    void notificationFailureClearlyPreservesTheInventoryChange() {
        InventoryService service = stockedService((sku, units) -> { throw new IllegalStateException("mail down"); });
        capture.events.clear();
        service.reserve("order", "sku", 5);

        ILoggingEvent failure = first("notification_failed");
        assertEquals(Level.WARN, failure.getLevel());
        assertNotNull(failure.getThrowableProxy());
        assertTrue(failure.getFormattedMessage().contains("inventoryChangePreserved=true retryScheduled=false"));
        assertEquals(1, events("operation_completed").size());
        assertEquals(0, events("notification_completed").size());
        assertEquals(5, service.available("sku"));
    }

    @Test
    void nestedCallbackSharesTraceAndRestoresParentOperation() {
        AtomicReference<InventoryService> reference = new AtomicReference<>();
        AtomicReference<Map<String, String>> parent = new AtomicReference<>();
        InventoryService service = stockedService((sku, units) -> {
            Map<String, String> before = MDC.getCopyOfContextMap();
            parent.set(before);
            assertEquals(units, reference.get().available(sku));
            assertEquals(before, MDC.getCopyOfContextMap());
        });
        reference.set(service);
        capture.events.clear();
        service.reserve("order", "sku", 5);

        ILoggingEvent nested = first("availability_read");
        assertEquals(parent.get().get("traceId"), nested.getMDCPropertyMap().get("traceId"));
        assertEquals(parent.get().get("operationId"), nested.getMDCPropertyMap().get("parentOperationId"));
        assertNotEquals(parent.get().get("operationId"), nested.getMDCPropertyMap().get("operationId"));
        assertFalse(nested.getMDCPropertyMap().containsKey("orderId"));
        assertFalse(nested.getMDCPropertyMap().containsKey("quantity"));
        assertNull(MDC.getCopyOfContextMap());
    }

    @Test
    void reusedWorkerThreadDoesNotLeakContextAcrossOperations() throws Exception {
        InventoryService service = stockedService((sku, units) -> {});
        capture.events.clear();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                service.reserve("first", "sku", 1);
                assertNull(MDC.getCopyOfContextMap());
            }).get(5, TimeUnit.SECONDS);
            executor.submit(() -> {
                service.reserve("second", "sku", 1);
                assertNull(MDC.getCopyOfContextMap());
            }).get(5, TimeUnit.SECONDS);
        }
        List<ILoggingEvent> created = events("reservation_created");
        assertEquals(2, created.size());
        assertEquals("first", created.getFirst().getMDCPropertyMap().get("orderId"));
        assertEquals("second", created.getLast().getMDCPropertyMap().get("orderId"));
        assertNotEquals(created.getFirst().getMDCPropertyMap().get("traceId"),
                created.getLast().getMDCPropertyMap().get("traceId"));
    }

    @Test
    void concurrentCallsKeepTheirOwnTraceAndOperationIdentifiers() throws Exception {
        InventoryService service = stockedService((sku, units) -> {});
        capture.events.clear();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String orderId = "order-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    MDC.put("traceId", "trace-" + orderId);
                    try {
                        service.reserve(orderId, "sku", 1);
                        assertEquals(Map.of("traceId", "trace-" + orderId), MDC.getCopyOfContextMap());
                    } finally {
                        MDC.clear();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }
        List<ILoggingEvent> created = events("reservation_created");
        assertEquals(8, created.size());
        assertEquals(8, created.stream().map(event -> event.getMDCPropertyMap().get("operationId")).distinct().count());
        for (ILoggingEvent event : capture.events) {
            String orderId = event.getMDCPropertyMap().get("orderId");
            assertEquals("trace-" + orderId, event.getMDCPropertyMap().get("traceId"));
        }
    }

    @Test
    void infoLevelKeepsMutationsAndOmitsReadAndLockDetails() {
        logger.setLevel(Level.INFO);
        InventoryService service = stockedService((sku, units) -> {});
        capture.events.clear();
        service.available("sku");
        assertTrue(capture.events.isEmpty());
        service.reserve("order", "sku", 1);
        assertEquals(1, events("reservation_created").size());
        assertEquals(1, events("operation_started").size());
        assertEquals(1, events("operation_completed").size());
        assertTrue(events("store_operation_finished").isEmpty());
    }

    @Test
    void loggedIdentifiersAreSanitizedWithoutChangingBusinessIdentifiers() {
        InventoryService service = Inventory.create(clock, (sku, units) -> {});
        String sku = "sku\r\nFORGED\tENTRY";
        String orderId = "order\nFORGED";
        service.registerProduct(sku, ProductCategory.STANDARD);
        service.addStock(sku, 10);
        capture.events.clear();
        Reservation reservation = service.reserve(orderId, sku, 1);
        assertEquals(sku, reservation.sku());
        assertEquals(orderId, reservation.orderId());
        for (ILoggingEvent event : capture.events) {
            assertFalse(event.getFormattedMessage().contains("\n"));
            assertFalse(event.getFormattedMessage().contains("\r"));
            assertFalse(event.getMDCPropertyMap().get("sku").contains("\n"));
            assertFalse(event.getMDCPropertyMap().get("orderId").contains("\n"));
        }
    }

    private InventoryService stockedService(com.store.inventory.api.StockAlertListener listener) {
        InventoryService service = Inventory.create(clock, listener);
        service.registerProduct("sku", ProductCategory.STANDARD);
        service.addStock("sku", 10);
        return service;
    }

    private List<ILoggingEvent> events(String event) {
        return capture.events.stream()
                .filter(entry -> entry.getFormattedMessage().startsWith("event=" + event + " ")
                        || entry.getFormattedMessage().equals("event=" + event))
                .toList();
    }

    private ILoggingEvent first(String event) {
        return events(event).getFirst();
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            // Capture MDC while the event's original thread and scope still exist.
            event.prepareForDeferredProcessing();
            events.add(event);
        }
    }

    private static final class AdjustableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }

        @Override
        public Instant instant() { return now; }
    }
}

package com.store.inventory.application.observability;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.domain.ReservationUnavailableException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

/** Adds operation tracing without owning inventory rules or mutable business state. */
public final class LoggingInventoryService implements InventoryService {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingInventoryService.class);
    private final InventoryService delegate;

    public LoggingInventoryService(InventoryService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        execute("registerProduct", null, sku, null, () -> {
            delegate.registerProduct(sku, category);
            return null;
        });
    }

    @Override
    public void addStock(String sku, int quantity) {
        execute("addStock", null, sku, quantity, () -> {
            delegate.addStock(sku, quantity);
            return null;
        });
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        return execute("reserve", orderId, sku, quantity, () -> delegate.reserve(orderId, sku, quantity));
    }

    @Override
    public void confirm(String orderId) {
        execute("confirm", orderId, null, null, () -> {
            delegate.confirm(orderId);
            return null;
        });
    }

    @Override
    public int available(String sku) {
        return execute("available", null, sku, null, () -> delegate.available(sku));
    }

    private <T> T execute(String operation, String orderId, String sku, Integer quantity, Supplier<T> task) {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        long started = System.nanoTime();
        boolean read = operation.equals("available");
        try {
            String traceId = MDC.get("traceId");
            String parentOperationId = MDC.get("operationId");
            MDC.put("traceId", traceId == null || traceId.isBlank()
                    ? UUID.randomUUID().toString() : LogValues.safe(traceId));
            MDC.put("operationId", UUID.randomUUID().toString());
            setContext("parentOperationId", parentOperationId);
            MDC.put("operation", operation);
            setContext("orderId", orderId);
            setContext("sku", sku);
            setContext("quantity", quantity == null ? null : quantity.toString());
            operationLevel(read).log("event=operation_started");
            T result = task.get();
            operationLevel(read).log("event=operation_completed outcome=success durationMicros={}", elapsed(started));
            return result;
        } catch (RuntimeException failure) {
            if (isExpectedRejection(failure)) {
                LOG.warn("event=operation_rejected outcome=rejected errorType={} reason={} durationMicros={}",
                        failure.getClass().getSimpleName(), LogValues.safe(failure.getMessage()), elapsed(started));
            } else {
                LOG.error("event=operation_failed outcome=failure errorType={} durationMicros={}",
                        failure.getClass().getSimpleName(), elapsed(started), failure);
            }
            throw failure;
        } catch (Error failure) {
            LOG.error("event=operation_failed outcome=failure errorType={} durationMicros={}",
                    failure.getClass().getSimpleName(), elapsed(started), failure);
            throw failure;
        } finally {
            // Restore the caller's context, including when callbacks invoke another operation.
            if (previousContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousContext);
            }
        }
    }

    private static LoggingEventBuilder operationLevel(boolean read) {
        return read ? LOG.atDebug() : LOG.atInfo();
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
    }

    private static void setContext(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, LogValues.safe(value));
        }
    }

    private static boolean isExpectedRejection(RuntimeException failure) {
        return failure instanceof InsufficientStockException
                || failure instanceof OrderLimitExceededException
                || failure instanceof IllegalArgumentException
                || failure instanceof ArithmeticException
                || failure instanceof ReservationUnavailableException;
    }
}

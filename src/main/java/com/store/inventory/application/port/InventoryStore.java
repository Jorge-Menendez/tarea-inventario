package com.store.inventory.application.port;

import java.util.function.Function;

/**
 * Executes a complete inventory operation with exclusive access to its state.
 * Session objects and mutable aggregates must not escape the callback.
 * Implementations must serialize competing operations on the same inventory.
 * This port promises isolation, not rollback on exceptions.
 */
public interface InventoryStore {
    <T> T executeExclusive(Function<InventorySession, T> operation);
}

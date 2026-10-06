package com.store.inventory.application.port;

import com.store.inventory.domain.ProductStock;
import com.store.inventory.domain.ReservationEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Access restricted to a single exclusive operation. */
public interface InventorySession {
    Optional<ProductStock> findProduct(String sku);
    void addProduct(String sku, ProductStock product);
    Optional<ReservationEntry> findOrder(String orderId);
    void addOrder(ReservationEntry order);
    List<ReservationEntry> removeDueReservations(Instant now);
}

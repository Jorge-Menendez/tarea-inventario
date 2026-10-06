package com.store.inventory.domain;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import java.util.Objects;
import java.util.OptionalInt;

/** Stock accounting aggregate. Its owner must serialize access. */
public final class ProductStock {
    private static final int LOW_STOCK_THRESHOLD = 5;
    private final ProductCategory category;
    private int availableUnits;
    private int unsoldUnits;
    private boolean lowStockReported;

    public ProductStock(ProductCategory category) {
        this.category = Objects.requireNonNull(category, "category");
    }

    public ProductCategory category() {
        return category;
    }

    public int availableUnits() {
        return availableUnits;
    }

    public void replenish(int quantity) {
        requirePositive(quantity);
        // Include held units so releasing reservations cannot overflow later.
        int updatedTotal = Math.addExact(unsoldUnits, quantity);
        unsoldUnits = updatedTotal;
        availableUnits += quantity;
        lowStockReported = false;
    }

    public void reserve(String sku, int quantity) {
        requirePositive(quantity);
        if (quantity > availableUnits) {
            throw new InsufficientStockException(sku, quantity, availableUnits);
        }
        availableUnits -= quantity;
    }

    public void release(int quantity) {
        requirePositive(quantity);
        if (quantity > unsoldUnits - availableUnits) {
            throw new IllegalStateException("Cannot release more units than reserved");
        }
        availableUnits += quantity;
    }

    public void sell(int quantity) {
        requirePositive(quantity);
        if (quantity > unsoldUnits - availableUnits) {
            throw new IllegalStateException("Cannot sell more units than reserved");
        }
        unsoldUnits -= quantity;
    }

    public OptionalInt claimLowStockAlert() {
        if (availableUnits <= LOW_STOCK_THRESHOLD && !lowStockReported) {
            lowStockReported = true;
            return OptionalInt.of(availableUnits);
        }
        return OptionalInt.empty();
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
    }
}

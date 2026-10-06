package com.store.inventory.application.port;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ReservationPolicy;

@FunctionalInterface
public interface ReservationPolicies {
    ReservationPolicy forCategory(ProductCategory category);
}

package com.store.inventory.application;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.application.port.ReservationPolicies;
import com.store.inventory.domain.ReservationPolicy;
import java.time.Duration;
import java.util.Map;

/** Immutable rules registry; applications can inject a different registry. */
public final class CategoryPolicies implements ReservationPolicies {
    private final Map<ProductCategory, ReservationPolicy> policies;

    public CategoryPolicies(Map<ProductCategory, ReservationPolicy> policies) {
        this.policies = Map.copyOf(policies);
    }

    public static CategoryPolicies defaults() {
        return new CategoryPolicies(Map.of(
                ProductCategory.STANDARD, new ReservationPolicy(Duration.ofMinutes(15), Integer.MAX_VALUE),
                ProductCategory.PRE_ORDER, new ReservationPolicy(Duration.ofHours(24), Integer.MAX_VALUE),
                ProductCategory.FLASH_SALE, new ReservationPolicy(Duration.ofMinutes(5), 2)));
    }

    @Override
    public ReservationPolicy forCategory(ProductCategory category) {
        ReservationPolicy policy = policies.get(category);
        if (policy == null) {
            throw new IllegalArgumentException("No policy for category " + category);
        }
        return policy;
    }
}

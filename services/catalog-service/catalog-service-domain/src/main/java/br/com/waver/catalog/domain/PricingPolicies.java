package br.com.waver.catalog.domain;

import java.util.List;

/** Port for PricingRule and DiscountPolicy. A reseller sees the global ones, its tier's and its own (RLS). */
public interface PricingPolicies {

    void insert(PricingRule rule);

    void insert(DiscountPolicy policy);

    List<PricingRule> rules();

    List<DiscountPolicy> discountPolicies();
}

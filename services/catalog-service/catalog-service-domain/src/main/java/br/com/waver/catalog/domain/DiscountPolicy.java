package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * DiscountPolicy (RFC-0017 §4, D-04 provisional): how much a reseller may discount from the retail list price
 * without approval, and which approval policy decides above it (approval-service, Commercial §11.1).
 */
public record DiscountPolicy(UUID id, PolicyTarget target, BigDecimal maxDiscountWithoutApprovalPct, String approvalPolicyKey,
                             Instant createdAt) {

    /** Approval policy used when nothing else is configured (RFC-0017 §7). */
    public static final String DEFAULT_APPROVAL_POLICY = "DISCOUNT_ABOVE_POLICY";

    private static final Pattern POLICY_KEY = Pattern.compile("^[A-Z][A-Z0-9_]{2,59}$");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public DiscountPolicy {
        Objects.requireNonNull(id);
        Objects.requireNonNull(target);
        Objects.requireNonNull(maxDiscountWithoutApprovalPct);
        Objects.requireNonNull(approvalPolicyKey);
        Objects.requireNonNull(createdAt);
    }

    public static DiscountPolicy define(PolicyTarget target, BigDecimal maxDiscountWithoutApprovalPct, String approvalPolicyKey, Instant now) {
        if (maxDiscountWithoutApprovalPct == null || maxDiscountWithoutApprovalPct.signum() < 0
                || maxDiscountWithoutApprovalPct.compareTo(HUNDRED) > 0) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_DISCOUNT_LIMIT_INVALID",
                    "The discount limit is a percentage between 0 and 100.");
        }
        String key = approvalPolicyKey == null || approvalPolicyKey.isBlank() ? DEFAULT_APPROVAL_POLICY : approvalPolicyKey.strip();
        if (!POLICY_KEY.matcher(key).matches()) {
            throw new DomainException(DomainException.Kind.VALIDATION, "CATALOG_APPROVAL_POLICY_INVALID",
                    "Approval policy key: uppercase letters, digits and underscores.");
        }
        return new DiscountPolicy(Ids.newId(), target, maxDiscountWithoutApprovalPct.setScale(4, RoundingMode.HALF_EVEN), key, now);
    }
}

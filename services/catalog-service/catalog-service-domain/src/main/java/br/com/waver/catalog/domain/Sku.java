package br.com.waver.catalog.domain;

import br.com.waver.kernel.error.DomainException;
import br.com.waver.kernel.id.Ids;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SKU (RFC-0017 §4): the sellable unit of a Product. {@code chargeType} says how it is charged (recurring,
 * by usage, once); prices live in price books, never here.
 */
public record Sku(UUID id, UUID productId, String skuCode, Unit unit, ChargeType chargeType, BillingPeriod billingPeriod,
                  String meterKey) {

    public enum Unit { WORKSPACE, CONNECTION, NUMBER, SEAT, RUN, TOKEN, HOUR }

    public enum ChargeType { RECURRING, USAGE, ONE_TIME }

    public enum BillingPeriod { MONTHLY, YEARLY }

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,59}$");
    private static final Pattern METER = Pattern.compile("^[a-z][a-z0-9._-]{2,79}$");

    public Sku {
        Objects.requireNonNull(id);
        Objects.requireNonNull(productId);
        Objects.requireNonNull(skuCode);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(chargeType);
    }

    /**
     * Recurring SKUs need a billing period; usage SKUs need the meter that measures them (usage-metering-service);
     * one-time SKUs have neither period nor meter.
     */
    public static Sku define(UUID productId, String skuCode, Unit unit, ChargeType chargeType, BillingPeriod period, String meterKey) {
        String code = skuCode == null ? "" : skuCode.strip().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw invalid("CATALOG_SKU_CODE_INVALID", "SKU code: 3 to 60 letters, digits or hyphens, e.g. WV-AGENT-MONTHLY.");
        }
        if (unit == null || chargeType == null) {
            throw invalid("CATALOG_SKU_INCOMPLETE", "Inform the unit and the charge type.");
        }
        String meter = meterKey == null || meterKey.isBlank() ? null : meterKey.strip();
        switch (chargeType) {
            case RECURRING -> {
                if (period == null) {
                    throw invalid("CATALOG_SKU_PERIOD_REQUIRED", "A recurring SKU needs a billing period.");
                }
                if (meter != null) {
                    throw invalid("CATALOG_SKU_METER_UNEXPECTED", "Only usage SKUs have a meter.");
                }
            }
            case USAGE -> {
                if (meter == null || !METER.matcher(meter).matches()) {
                    throw invalid("CATALOG_SKU_METER_REQUIRED", "A usage SKU needs the meter key, e.g. agent.runs.");
                }
            }
            case ONE_TIME -> {
                if (period != null || meter != null) {
                    throw invalid("CATALOG_SKU_ONE_TIME_INVALID", "A one-time SKU has no billing period and no meter.");
                }
            }
        }
        return new Sku(Ids.newId(), productId, code, unit, chargeType, period, meter);
    }

    public Optional<BillingPeriod> period() {
        return Optional.ofNullable(billingPeriod);
    }

    public Optional<String> meter() {
        return Optional.ofNullable(meterKey);
    }

    private static DomainException invalid(String code, String message) {
        return new DomainException(DomainException.Kind.VALIDATION, code, message);
    }
}

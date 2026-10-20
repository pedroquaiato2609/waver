package br.com.waver.platform.tenancy;

import br.com.waver.kernel.tenancy.TenantContext;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tenant context of the current unit of work (HTTP request, Kafka record, scheduled job).
 * Set only by the platform (token filter, consumer interceptor); application code reads it.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static Optional<TenantContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static TenantContext require() {
        TenantContext ctx = CURRENT.get();
        if (ctx == null) {
            throw new TenantContextMissingException();
        }
        return ctx;
    }

    public static <T> T callAs(TenantContext ctx, Supplier<T> work) {
        TenantContext previous = CURRENT.get();
        CURRENT.set(ctx);
        try {
            return work.get();
        } finally {
            restore(previous);
        }
    }

    public static void runAs(TenantContext ctx, Runnable work) {
        callAs(ctx, () -> {
            work.run();
            return null;
        });
    }

    static void set(TenantContext ctx) {
        CURRENT.set(ctx);
    }

    static void clear() {
        CURRENT.remove();
    }

    private static void restore(TenantContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** Raised when tenant-scoped work runs without a validated tenant context (AR-008). */
    public static final class TenantContextMissingException extends RuntimeException {
        public TenantContextMissingException() {
            super("Tenant context missing: the request was not authenticated with WAVER tenant claims");
        }
    }
}

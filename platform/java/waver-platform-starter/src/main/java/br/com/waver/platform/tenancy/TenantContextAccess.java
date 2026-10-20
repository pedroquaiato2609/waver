package br.com.waver.platform.tenancy;

import br.com.waver.kernel.tenancy.TenantContext;

/** Platform-internal setter for {@link TenantContextHolder}; application code must not call it. */
public final class TenantContextAccess {

    private TenantContextAccess() {
    }

    public static void set(TenantContext ctx) {
        TenantContextHolder.set(ctx);
    }

    public static void clear() {
        TenantContextHolder.clear();
    }
}

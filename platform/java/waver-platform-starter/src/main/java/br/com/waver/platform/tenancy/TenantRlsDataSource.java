package br.com.waver.platform.tenancy;

import br.com.waver.kernel.tenancy.TenantContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Sets the PostgreSQL session variables read by Row-Level Security policies (ADR-0010) every time a
 * connection is taken from the pool, and clears them when it is returned:
 *   waver.org_id           organization of the validated token ('' when there is no tenant context)
 *   waver.org_type         PLATFORM | RESELLER | CUSTOMER
 *   waver.platform_access  'on' only for WAVER staff on audited oversight paths
 * A missing setting makes the policies return zero rows (fail closed).
 */
public class TenantRlsDataSource extends DelegatingDataSource {

    private static final String SET_SQL =
            "select set_config('waver.org_id', ?, false), set_config('waver.org_type', ?, false), set_config('waver.platform_access', ?, false)";
    private static final String RESET_SQL =
            "select set_config('waver.org_id', '', false), set_config('waver.org_type', '', false), set_config('waver.platform_access', 'off', false)";

    public TenantRlsDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTenant(super.getConnection(username, password));
    }

    private Connection withTenant(Connection connection) throws SQLException {
        TenantContext ctx = TenantContextHolder.current().orElse(null);
        try (PreparedStatement ps = connection.prepareStatement(SET_SQL)) {
            ps.setString(1, ctx == null ? "" : ctx.orgId().toString());
            ps.setString(2, ctx == null ? "" : ctx.orgType().name());
            ps.setString(3, ctx != null && ctx.isPlatformStaff() ? "on" : "off");
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, new ResettingHandler(connection));
    }

    private record ResettingHandler(Connection target) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && !target.isClosed()) {
                try (PreparedStatement ps = target.prepareStatement(RESET_SQL)) {
                    ps.execute();
                } catch (SQLException ignored) {
                    // broken connection: the pool evicts it
                }
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}

package io.astra.doctype.store;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

/**
 * Fail-closed startup check for tenant isolation. PostgreSQL superusers and roles with BYPASSRLS
 * ignore row-level security policies, so a kernel connected as one would serve every tenant's data
 * to every tenant without any error. Refusing to start is the only safe behaviour.
 */
@ApplicationScoped
public class RowLevelSecurityGuard {

    private static final Logger LOG = Logger.getLogger(RowLevelSecurityGuard.class);

    @Inject AgroalDataSource dataSource;

    @ConfigProperty(name = "astra.tenancy.allow-rls-bypass", defaultValue = "false")
    boolean allowBypass;

    void onStart(@Observes StartupEvent ev) {
        check(dataSource);
    }

    /**
     * Refuses a role that would bypass row-level security. Called by {@link RowLevelSecurityFlywayCustomizer}
     * before Flyway migrates at start, so an unsafe role cannot even run the schema migrations, and again at
     * startup for deployments that migrate separately.
     */
    void check(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
            if (!rs.next()) throw new IllegalStateException("cannot read role attributes for current_user");
            String role = rs.getString(1);
            boolean superuser = rs.getBoolean(2);
            boolean bypass = rs.getBoolean(3);
            if (superuser || bypass) {
                String msg = "Database role '" + role + "' is " + (superuser ? "a SUPERUSER" : "BYPASSRLS")
                        + ": row-level security would be ignored and tenants would see each other's data. "
                        + "Connect as a non-superuser application role (see deploy/docker/init/01-app-role.sql).";
                if (allowBypass) {
                    LOG.warn(msg + " Continuing because astra.tenancy.allow-rls-bypass=true. NEVER use this in production.");
                } else {
                    throw new IllegalStateException(msg);
                }
            } else {
                LOG.infof("Tenant isolation guard: role '%s' is subject to row-level security", role);
            }
            requireLeakproofAccessor(st);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot verify row-level security role attributes", e);
        }
    }

    /**
     * Under row-level security the planner uses an index for a predicate only when every function in it is
     * LEAKPROOF; {@code ->>} is not, so without {@code astra_field} every list filter scans the whole tenant
     * (measured: 470 ms instead of 0.13 ms on 5 million orders, docs/why_doctype_jsonb.md). Only a superuser
     * can declare it, so it comes from the bootstrap scripts, and the kernel refuses to start without it.
     */
    private static void requireLeakproofAccessor(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT p.proleakproof FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = '" + ProjectionIndexer.ACCESSOR_SCHEMA + "' AND p.proname = '"
                        + ProjectionIndexer.ACCESSOR_NAME + "' AND p.pronargs = 2")) {
            if (!rs.next()) {
                throw new IllegalStateException("Function " + ProjectionIndexer.ACCESSOR + "(jsonb, text) is missing: "
                        + "projected-field filters would scan the whole tenant under row-level security. "
                        + "Run deploy/db/05-astra-field.sql as the database superuser (scripts/db-setup.sh).");
            }
            if (!rs.getBoolean(1)) {
                throw new IllegalStateException("Function " + ProjectionIndexer.ACCESSOR + "(jsonb, text) is not LEAKPROOF: "
                        + "the planner would not use projection indexes under row-level security. "
                        + "Run deploy/db/05-astra-field.sql as the database superuser.");
            }
        }
    }
}

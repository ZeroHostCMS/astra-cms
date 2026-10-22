package io.astra.tx.seata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.astra.spi.tenant.TenantContext;
import io.astra.spi.tx.TccParticipant;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

import java.util.List;
import java.util.Map;

/**
 * Routes Seata's TCC calls to app participants. Phase 2 arrives on a Seata thread with no request, so
 * the tenant recorded at prepare time is bound again before the participant runs.
 */
public class AstraTccDispatcher implements AstraTccAction { // public, not final: Seata subclasses it in its own class loader

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final List<TccParticipant> participants;
    private final ObjectMapper json;

    /** For Seata's generated subclass, which forwards every call to the instance it was created around. */
    public AstraTccDispatcher() {
        this(List.of(), new ObjectMapper());
    }

    public AstraTccDispatcher(List<TccParticipant> participants, ObjectMapper json) {
        this.participants = participants;
        this.json = json;
    }

    // Seata reads the action and parameter annotations from the class it proxies, so they are repeated here.
    @Override
    @TwoPhaseBusinessAction(name = "astra-tcc", commitMethod = "commit", rollbackMethod = "rollback")
    public boolean prepare(BusinessActionContext context,
                           @BusinessActionContextParameter(paramName = "tenant") String tenant,
                           @BusinessActionContextParameter(paramName = "participant") String participant,
                           @BusinessActionContextParameter(paramName = "key") String key,
                           @BusinessActionContextParameter(paramName = "arguments") String argumentsJson) {
        String branch = context.getXid() + "/" + context.getBranchId();
        TenantContext.run(tenant, () -> find(participant).prepare(branch, key, arguments(argumentsJson)));
        return true;
    }

    @Override
    public boolean commit(BusinessActionContext context) {
        phase2(context, true);
        return true;
    }

    @Override
    public boolean rollback(BusinessActionContext context) {
        phase2(context, false);
        return true;
    }

    private void phase2(BusinessActionContext c, boolean commit) {
        String tenant = String.valueOf(c.getActionContext("tenant"));
        TccParticipant p = find(String.valueOf(c.getActionContext("participant")));
        String key = String.valueOf(c.getActionContext("key"));
        Map<String, Object> args = arguments(String.valueOf(c.getActionContext("arguments")));
        String branch = c.getXid() + "/" + c.getBranchId();
        TenantContext.run(tenant, () -> {
            if (commit) p.commit(branch, key, args);
            else p.rollback(branch, key, args);
        });
    }

    private TccParticipant find(String name) {
        return participants.stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("no TCC participant " + name));
    }

    private Map<String, Object> arguments(String text) {
        try {
            return text == null || text.equals("null") ? Map.of() : json.readValue(text, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("bad TCC arguments", e);
        }
    }
}

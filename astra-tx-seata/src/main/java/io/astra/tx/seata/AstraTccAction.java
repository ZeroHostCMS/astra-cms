package io.astra.tx.seata;

import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextParameter;
import org.apache.seata.rm.tcc.api.LocalTCC;
import org.apache.seata.rm.tcc.api.TwoPhaseBusinessAction;

/**
 * The one TCC resource Astra registers with Seata. Every app participant is a branch of it, told apart
 * by the {@code participant} parameter, so apps implement {@link io.astra.spi.tx.TccParticipant} and never
 * see a Seata type.
 */
@LocalTCC
public interface AstraTccAction {

    @TwoPhaseBusinessAction(name = "astra-tcc", commitMethod = "commit", rollbackMethod = "rollback")
    boolean prepare(BusinessActionContext context,
                    @BusinessActionContextParameter(paramName = "tenant") String tenant,
                    @BusinessActionContextParameter(paramName = "participant") String participant,
                    @BusinessActionContextParameter(paramName = "key") String key,
                    @BusinessActionContextParameter(paramName = "arguments") String argumentsJson);

    boolean commit(BusinessActionContext context);

    boolean rollback(BusinessActionContext context);
}

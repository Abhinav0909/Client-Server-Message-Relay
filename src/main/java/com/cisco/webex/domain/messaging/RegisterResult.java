package com.cisco.webex.domain.messaging;

import com.cisco.webex.domain.client.Client;

/**
 * Result of a REGISTER request. Pure value type — the decision of which
 * factory to call belongs to {@link MessagingServiceImpl}, not here.
 */
public record RegisterResult(Status status, Client session, String code, String reason) {

    public enum Status { OK, ERROR }

    public static RegisterResult ok(Client session) {
        return new RegisterResult(Status.OK, session, null, null);
    }

    public static RegisterResult error(String code, String reason) {
        return new RegisterResult(Status.ERROR, null, code, reason);
    }
}

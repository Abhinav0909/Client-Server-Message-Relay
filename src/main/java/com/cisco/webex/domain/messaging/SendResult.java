package com.cisco.webex.domain.messaging;

/**
 * Result of a SEND request. Pure value type — the decision of which
 * factory to call belongs to {@link MessagingServiceImpl}, not here.
 */
public record SendResult(Status status, String code, String reason) {

    public enum Status { ACCEPTED, REJECTED }

    public static SendResult accepted() {
        return new SendResult(Status.ACCEPTED, null, null);
    }

    public static SendResult rejected(String code, String reason) {
        return new SendResult(Status.REJECTED, code, reason);
    }
}

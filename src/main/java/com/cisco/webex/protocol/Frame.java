package com.cisco.webex.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire-format protocol frame — a flat DTO covering every op. Pure data
 * holder confined to the protocol and orchestration packages; never
 * crosses into domain code (see {@code ClientChannel#deliver}, which
 * takes a domain {@code Message}, not a Frame).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Frame {

    private Operations op;

    private String clientId;   // REGISTER
    private String msgId;      // SEND, SEND_ACK, DELIVER, ACK
    private String from;       // DELIVER, ACK (identifies which sender's message is being acknowledged)
    private String to;         // SEND
    private String payload;    // SEND, DELIVER
    private String status;     // SEND_ACK: "ACCEPTED" | "REJECTED"
    private String code;       // REGISTER_ERR, ERROR: machine-readable code
    private String reason;     // REGISTER_ERR, SEND_ACK, ERROR: human detail

    public Frame() {
    }

    public static Frame of(Operations op) {
        Frame f = new Frame();
        f.setOp(op);
        return f;
    }

    public Operations getOp() {
        return op;
    }

    public void setOp(Operations op) {
        this.op = op;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    @Override
    public String toString() {
        return "Frame{" +
                "op=" + op +
                ", clientId='" + clientId + '\'' +
                ", msgId='" + msgId + '\'' +
                ", from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", status='" + status + '\'' +
                ", code='" + code + '\'' +
                '}';
    }
}

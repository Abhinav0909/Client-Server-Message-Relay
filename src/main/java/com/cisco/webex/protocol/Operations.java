package com.cisco.webex.protocol;

/** Wire-level operation codes. REGISTER/SEND/ACK are client-to-server; the rest are server-to-client. */
public enum Operations {
    REGISTER, REGISTER_OK, REGISTER_ERR,
    SEND, SEND_ACK,
    DELIVER, ACK,
    ERROR
}

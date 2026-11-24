package com.example.fixgateway.application.port;

/** The executions already published, once per order and exec id. */
public interface Executions {

    /** @return false if this execution was reported before (a resend, a PossDupFlag copy) */
    boolean markReported(String orderId, String execId);
}

package com.example.savings.domain;

public enum HoldStatus {
    /** Funds are earmarked, waiting for Payments to confirm or cancel. */
    RESERVED,
    /** Transfer executed, the amount was debited. Terminal. */
    SETTLED,
    /** Transfer cancelled, funds returned. Terminal. */
    RELEASED,
    /** No answer in time, funds returned. A late confirmation can still settle it. */
    EXPIRED
}

package com.example.investments.application;

/**
 * An instruction of the transfer service that contradicts earlier ones: a transfer id answered once
 * and sent again for another amount, a return of a withdrawal that was never carried out. A contract
 * violation: the message is dead-lettered for a person (ADR 0007).
 */
public class IllegalInstructionException extends RuntimeException {

    public IllegalInstructionException(String message) {
        super(message);
    }
}

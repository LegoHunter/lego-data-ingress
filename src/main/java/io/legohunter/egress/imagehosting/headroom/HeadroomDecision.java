package io.legohunter.egress.imagehosting.headroom;

/** Whether new photos may be published now, and why not when they may not. */
public record HeadroomDecision(boolean allowed, String message, AccountHeadroom headroom) {
}

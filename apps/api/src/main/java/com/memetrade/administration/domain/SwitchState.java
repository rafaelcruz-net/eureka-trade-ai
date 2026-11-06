package com.memetrade.administration.domain;

/** State of the kill switch (entities.md KillSwitchState.state); stored and sent with the same upper-case name. */
public enum SwitchState {
    ON,
    OFF;

    /** True for the state that keeps every bot paused by the system. */
    public boolean paused() {
        return this == ON;
    }
}

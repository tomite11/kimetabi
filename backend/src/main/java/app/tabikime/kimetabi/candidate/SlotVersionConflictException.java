package app.tabikime.kimetabi.candidate;

import app.tabikime.kimetabi.trip.SlotResource;

public class SlotVersionConflictException extends RuntimeException {

    private final SlotResource current;

    public SlotVersionConflictException(SlotResource current) {
        this.current = current;
    }

    public SlotResource current() {
        return current;
    }
}

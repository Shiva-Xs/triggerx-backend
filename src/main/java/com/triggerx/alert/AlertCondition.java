package com.triggerx.alert;

public enum AlertCondition {
    ABOVE,
    BELOW,
    CROSSES;

    public String label() {
        return switch (this) {
            case ABOVE   -> "above";
            case BELOW   -> "below";
            case CROSSES -> "crosses";
        };
    }

    public String actionPhrase() {
        return switch (this) {
            case ABOVE   -> "crossed above";
            case BELOW   -> "dropped below";
            case CROSSES -> "touched";
        };
    }
}

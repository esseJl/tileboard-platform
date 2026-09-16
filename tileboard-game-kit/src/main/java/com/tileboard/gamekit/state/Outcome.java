package com.tileboard.gamekit.state;

/**
 * The built-in "win / loss" capability's result type. Kept here rather
 * than inside any specific game so the concept - and its {@link #isFinal()}
 * check - is shared by every game rather than each one re-declaring its
 * own outcome enum.
 */
public enum Outcome {
    /** The round is still in progress. */
    IN_PROGRESS,
    WON,
    LOST,
    /** For competitive/group modes where neither side prevailed. */
    DRAW;

    /** Whether this outcome ends the round - i.e. anything other than {@link #IN_PROGRESS}. */
    public boolean isFinal() {
        return this != IN_PROGRESS;
    }
}

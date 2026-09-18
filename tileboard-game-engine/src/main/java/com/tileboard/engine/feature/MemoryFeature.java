package com.tileboard.engine.feature;

import com.tileboard.serial.board.Position;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in memory-game support: stores a target sequence, lets the player
 * replicate it, and scores correctness.
 */
public final class MemoryFeature {

    private volatile List<Position>  targetSequence = List.of();
    private final    List<Position>  playerInput    = Collections.synchronizedList(new ArrayList<>());

    public void setTarget(List<Position> sequence) {
        this.targetSequence = List.copyOf(sequence);
        playerInput.clear();
    }

    public void addInput(Position position) { playerInput.add(position); }

    public boolean isComplete() { return playerInput.size() >= targetSequence.size(); }

    /** {@code true} if the player's input so far matches the target prefix. */
    public boolean isCorrectSoFar() {
        List<Position> input = List.copyOf(playerInput);
        if (input.size() > targetSequence.size()) return false; // player over-input: fail fast, no crash
        for (int i = 0; i < input.size(); i++) {
            if (!input.get(i).equals(targetSequence.get(i))) return false;
        }
        return true;
    }

    public boolean isFullyCorrect() {
        return isComplete() && List.copyOf(playerInput).equals(targetSequence);
    }

    public int targetLength() { return targetSequence.size(); }

    public int inputLength()  { return playerInput.size(); }

    public void resetInput()  { playerInput.clear(); }

    public List<Position> target() { return targetSequence; }
}
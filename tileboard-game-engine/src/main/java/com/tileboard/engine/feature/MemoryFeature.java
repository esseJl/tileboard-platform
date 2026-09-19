package com.tileboard.engine.feature;

import com.tileboard.serial.board.Position;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Built-in memory-game support: stores a target sequence, lets the player
 * replicate it, and scores correctness.
 */
public final class MemoryFeature {

    private final List<Position> playerInput = Collections.synchronizedList(new ArrayList<>());
    private volatile List<Position> targetSequence = List.of();

    public void setTarget(List<Position> sequence) {
        this.targetSequence = List.copyOf(sequence);
        playerInput.clear();
    }

    public void addInput(Position position) {
        playerInput.add(position);
    }

    public boolean isComplete() {
        return playerInput.size() >= targetSequence.size();
    }

    /**
     * {@code true} if the player's input so far matches the target prefix.
     */
    public boolean isCorrectSoFar() {
        return MemoryEvaluator.isCorrectSoFar(targetSequence, List.copyOf(playerInput));
    }

    public int targetLength() {
        return targetSequence.size();
    }

    public int inputLength() {
        return playerInput.size();
    }

    public void resetInput() {
        playerInput.clear();
    }

    public List<Position> target() {
        return targetSequence;
    }

    public boolean isFullyCorrect() {
        return MemoryEvaluator.isFullyCorrect(targetSequence, List.copyOf(playerInput));
    }

    public static final class MemoryEvaluator {
        public static boolean isCorrectSoFar(
                List<Position> target, List<Position> input) {
            if (input.size() > target.size()) return false;
            for (int i = 0; i < input.size(); i++) {
                if (!input.get(i).equals(target.get(i))) return false;
            }
            return true;
        }

        public static boolean isFullyCorrect(List<Position> target, List<Position> input) {
            return input.size() == target.size() && isCorrectSoFar(target, input);
        }
    }
}
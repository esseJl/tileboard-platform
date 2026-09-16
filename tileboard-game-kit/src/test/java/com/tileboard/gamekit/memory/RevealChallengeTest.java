package com.tileboard.gamekit.memory;

import com.tileboard.serial.board.Position;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RevealChallengeTest {

    private static final Position P00 = new Position(0, 0);
    private static final Position P01 = new Position(0, 1);
    private static final Position P10 = new Position(1, 0);
    private static final Position P11 = new Position(1, 1);

    @Test
    void matchingPairIsSolvedAndStaysFaceUp() {
        RevealChallenge<String> challenge = new RevealChallenge<>(Map.of(
                P00, "red", P01, "red", P10, "blue", P11, "blue"));

        assertTrue(challenge.registerPick(P00));
        assertTrue(challenge.registerPick(P01));
        assertEquals(RevealChallenge.PickResult.MATCHED, challenge.evaluatePendingPair());

        assertTrue(challenge.isFaceUp(P00));
        assertTrue(challenge.isFaceUp(P01));
        assertEquals(1, challenge.solvedPairs());
        assertFalse(challenge.isComplete());
        assertFalse(challenge.isEligiblePick(P00), "a solved cell can't be picked again");
    }

    @Test
    void mismatchClearsPicksWithoutSolving() {
        RevealChallenge<String> challenge = new RevealChallenge<>(Map.of(
                P00, "red", P01, "green", P10, "blue", P11, "blue"));

        challenge.registerPick(P00);
        challenge.registerPick(P01);
        assertEquals(RevealChallenge.PickResult.MISMATCHED, challenge.evaluatePendingPair());

        assertFalse(challenge.isFaceUp(P00));
        assertFalse(challenge.isFaceUp(P01));
        assertTrue(challenge.isEligiblePick(P00), "an unsolved cell is pickable again after a mismatch");
    }
}

package com.tileboard.app.service.game.engine.event;

/**
 * Functional listener for high-level game outcomes.
 * Games (or the session manager) can register multiple listeners.
 */
@FunctionalInterface
public interface GameEventListener {

    void onEvent(GameEvent event);

    enum GameEventType {
        WIN,
        LOSS,
        SCORE_CHANGED,
        LEVEL_UP,
        COMBO,
        ROUND_START,
        ROUND_END,
        TIMEOUT
    }

    record GameEvent(GameEventType type, String message, int value) {
        public static GameEvent win(String message) {
            return new GameEvent(GameEventType.WIN, message, 0);
        }

        public static GameEvent loss(String message) {
            return new GameEvent(GameEventType.LOSS, message, 0);
        }

        public static GameEvent score(int score) {
            return new GameEvent(GameEventType.SCORE_CHANGED, "score", score);
        }

        public static GameEvent levelUp(int level) {
            return new GameEvent(GameEventType.LEVEL_UP, "level", level);
        }

        public static GameEvent combo(int combo) {
            return new GameEvent(GameEventType.COMBO, "combo", combo);
        }

        public static GameEvent timeout() {
            return new GameEvent(GameEventType.TIMEOUT, "timeout", 0);
        }
    }
}

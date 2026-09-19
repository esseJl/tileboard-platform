package com.tileboard.engine.core;

import com.tileboard.engine.model.Player;

import java.util.List;

public interface SessionControl {
    String sessionId();

    GameStatus status();
    
    List<Player> players();

    GameDescriptor descriptor();

    GameState state();

    void winSession(List<Player> winners);

    void loseSession();

    void stopSession();
}

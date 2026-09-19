package com.tileboard.engine.core;

import com.tileboard.engine.event.GameEventBus;
import com.tileboard.engine.feature.*;
import com.tileboard.engine.feature.neighbor.NeighborFinder;
import com.tileboard.engine.model.Player;
import com.tileboard.engine.model.TileColor;
import com.tileboard.serial.board.Board;
import org.springframework.lang.NonNull;

import java.util.List;

/**
 * The single object passed to every {@link GameLifecycle} method. It is the
 * game's window onto the engine: board I/O, built-in features, event
 * publishing and session control all go through here.
 *
 * <p>All methods are thread-safe (either delegating to thread-safe
 * subsystems or synchronised internally).
 */
public interface GameContext extends BoardContext, SessionControl, FeatureProvider {

    GameEventBus eventBus();
}
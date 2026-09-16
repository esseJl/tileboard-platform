/**
 * Tileboard Game Kit: a framework-free, thread-safe library of reusable
 * "game building blocks" that sits between {@code tileboard-serial-protocol}
 * (the transport-agnostic wire library) and any concrete game engine
 * integration (e.g. {@code tileboard-app}'s {@code com.tileboard.app.gameengine}).
 *
 * <h2>Why a separate module</h2>
 * <p>{@code tileboard-app}'s game engine ({@code Game}, {@code GameContext},
 * {@code GameFactory}/{@code GameRegistry}) already gets the platform's
 * <em>lifecycle</em> right: how a game starts/stops, how touches are
 * decoded, how frames are sent. What used to be missing was a shared,
 * tested place for the <em>gameplay building blocks</em> every real game
 * needs - touch bookkeeping, movement patterns, scoring, timers,
 * multiplayer rosters - so each new game stopped re-deriving its own
 * (subtly different, not always thread-safe) version of the same logic.
 * This module is that place, isolated behind its own Maven artifact so it
 * has zero dependency on Spring, HTTP, or the serial transport - it only
 * depends on {@code tileboard-serial-protocol} for {@code Board}/{@code Position},
 * and can be reused by any future integration (a desktop app, a CLI, a
 * different transport) exactly as-is.
 *
 * <h2>How a game uses it</h2>
 * <p>A game composes only the capabilities it needs as plain fields -
 * there is no base class to extend and no capability a game is forced to
 * carry. For example a reflex game might hold a
 * {@link com.tileboard.gamekit.touch.TouchTracker}, a
 * {@link com.tileboard.gamekit.state.HealthTracker} and a
 * {@link com.tileboard.gamekit.pattern.MovementPattern}; a memory game
 * instead holds a {@link com.tileboard.gamekit.memory.RevealChallenge}. A
 * game with many moving parts can additionally use
 * {@link com.tileboard.gamekit.capability.GameToolkit} as a single,
 * type-safe bag to carry them all in one field.
 *
 * <h2>How to add a new capability</h2>
 * <p>Write a new, independent class (thread-safe, and a
 * {@code @FunctionalInterface} if it is a single-method extension point,
 * following the pattern every class in this package already uses) in
 * whichever sub-package fits - or a brand-new sub-package - and use it
 * from a game via a plain field or via {@code GameToolkit.register}. No
 * class in this kit, and no class in {@code tileboard-app}'s game engine,
 * ever needs to change: this mirrors how {@code GameRegistry} already
 * lets new <em>games</em> be added purely additively, applied one level
 * down to the building blocks a game is made of.
 *
 * <h2>Thread-safety contract</h2>
 * <p>Every stateful class in this kit is safe to touch from more than one
 * thread without external synchronization, because that is exactly the
 * situation every real game session is in: an HTTP thread runs
 * {@code start()}, a serial gateway callback thread reports touches, and a
 * dedicated clock thread drives any scheduled ticks. Pure, stateless
 * classes ({@link com.tileboard.gamekit.pattern.MovementPattern} instances,
 * {@link com.tileboard.gamekit.geometry.Neighbors} instances) need no such
 * guarantee and are documented as such.
 */
package com.tileboard.gamekit;

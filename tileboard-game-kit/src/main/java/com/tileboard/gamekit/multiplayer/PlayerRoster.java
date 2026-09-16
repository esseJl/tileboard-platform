package com.tileboard.gamekit.multiplayer;

import com.tileboard.gamekit.state.ScoreBoard;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The built-in "رقابت دو نفره" (two-player competition) and "بازی گروهی"
 * (group play) capability: a roster of {@link PlayerId}s, each with their
 * own {@link ScoreBoard}, optionally grouped into named teams. A plain
 * head-to-head game just registers two players and never mentions teams; a
 * group game additionally assigns each player a team name and can read
 * aggregate team scores.
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}, so registering a
 * late-joining player and reading scores for a leaderboard can happen from
 * different threads concurrently.
 */
public final class PlayerRoster {

    private final Map<PlayerId, ScoreBoard> scoreBoards = new ConcurrentHashMap<>();
    private final Map<PlayerId, String> teamsByPlayer = new ConcurrentHashMap<>();

    /** Convenience factory for the common two-player-competition case: registers exactly two players up front. */
    public static PlayerRoster twoPlayer(String firstLabel, String secondLabel) {
        PlayerRoster roster = new PlayerRoster();
        roster.register(new PlayerId(firstLabel));
        roster.register(new PlayerId(secondLabel));
        return roster;
    }

    /** Adds {@code id} to the roster (if not already present) with a fresh {@link ScoreBoard} and no team, and returns that score board. */
    public ScoreBoard register(PlayerId id) {
        return scoreBoards.computeIfAbsent(id, ignored -> new ScoreBoard());
    }

    /** {@link #register(PlayerId)}, additionally assigning {@code id} to {@code team} - the built-in "group play" case. */
    public ScoreBoard register(PlayerId id, String team) {
        ScoreBoard board = register(id);
        teamsByPlayer.put(id, team);
        return board;
    }

    /** @throws IllegalArgumentException if {@code id} was never {@link #register}ed */
    public ScoreBoard scoreBoardOf(PlayerId id) {
        ScoreBoard board = scoreBoards.get(id);
        if (board == null) {
            throw new IllegalArgumentException("Unknown player: " + id);
        }
        return board;
    }

    public Set<PlayerId> players() {
        return Set.copyOf(scoreBoards.keySet());
    }

    public Optional<String> teamOf(PlayerId id) {
        return Optional.ofNullable(teamsByPlayer.get(id));
    }

    /** Every registered player currently assigned to {@code team}. */
    public Set<PlayerId> playersInTeam(String team) {
        Set<PlayerId> members = new HashSet<>();
        teamsByPlayer.forEach((player, playerTeam) -> {
            if (playerTeam.equals(team)) {
                members.add(player);
            }
        });
        return Set.copyOf(members);
    }

    /** Sum of every team member's individual score - the built-in group-play aggregate. */
    public int teamScore(String team) {
        return playersInTeam(team).stream().mapToInt(player -> scoreBoardOf(player).score()).sum();
    }

    /** The registered player with the highest individual score, if any players are registered. Ties are broken arbitrarily. */
    public Optional<PlayerId> leader() {
        return scoreBoards.entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getValue().score()))
                .map(Map.Entry::getKey);
    }
}

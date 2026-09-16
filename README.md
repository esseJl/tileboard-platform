# Tileboard Platform

This repository replaces the old `TilesApplication` Spring Boot app with a
clean, layered rewrite built on top of the new `tileboard-serial-protocol`
library, keeping the same application logic (device geometry, serial port
management, game sessions, live board streaming) while fixing the structural
problems in the original code.

As requested, this pass **excludes**: concrete game implementations (Dooz,
Jump*, Snakes & Ladders, Color Match, ...), Spring Security, and any real
persistence layer. Those are wired as clean extension points so they can be
added incrementally without touching what's already here.

## Modules

```
tileboard-platform/
├── tileboard-serial-protocol/   the library (unchanged, as provided)
└── tileboard-app/               new Spring Boot application
```

## Why this structure

The old project mixed concerns badly: `SerialService` and
`EventHandlerService` each hand-rolled the same frame-building logic, port
names were a hard-coded Windows-only enum (`COM1`..`COM36`), and adding a
game meant adding a branch to one large `switch` in `GameEngineService`
that also happened to read scoring config from a repository. None of that
was necessary to reach production-ready code - it was just how the logic
had accreted.

The new app is organized by responsibility instead, and every module talks
to the next one through an interface:

| Package | Responsibility | Talks to |
|---|---|---|
| `device` | The board's width/height. One value, one interface (`DeviceConfigurationService`), one in-memory impl for now. | nobody |
| `serial` | Discovering ports, assigning IN/OUT roles, opening/closing the `TileGatewayClient` from the library. Publishes `GatewayConnectedEvent`/`GatewayDisconnectedEvent`. | `device` (for the handshake), the library |
| `gameengine` | The `Game`/`GameFactory`/`GameRegistry` extension point and `GameSessionManager`, which reacts to gateway events and wires touch input to whichever game is active. | `device`, `serial` (via events only), `streaming` |
| `streaming` | Fans out board frames to any number of SSE subscribers (a live dashboard mirror). | nobody |
| `common` | Exception hierarchy (`ApiException` + subclasses) and one `@RestControllerAdvice` translating them to RFC 7807 `ProblemDetail` responses. | nobody |
| `config` | Bean wiring (`SerialPortRegistry` impl) and `tileboard.serial.*` properties. | the library |

Nothing here reaches "sideways" into another module's internals - the game
engine, for instance, has never heard of `SerialPortRegistry` or a COM port;
it only knows "a `TileGatewayClient` became available" via an event.

## The game extension point

This is the part meant to make adding real games painless later:

```java
public interface Game<T> {
    GameDefinition definition();
    TileCodec<T> tileCodec();          // how this game's tiles map to wire bytes
    void start(GameContext<T> context);
    void onPlayerInput(Board<Boolean> touchedTiles);
    default void stop() {}
}

public interface GameFactory {
    String gameId();
    GameDefinition definition();
    Game<?> create(GameMode mode, int width, int height);
}
```

To add a game: write one class implementing `Game<T>` for whatever tile
type it needs (a color enum, `Boolean`, an intensity level - the library's
`TileCodec` doesn't care), write one `@Component` implementing `GameFactory`
to build it, and it is automatically picked up by `GameRegistry` (a plain
constructor-injected `List<GameFactory>` - no switch statement, no
registration list to maintain by hand) and playable via
`POST /api/v1/games/{gameId}/start`.

`gameengine/example/TouchEchoGame` is a minimal reference implementation
(lights up whatever tile is touched) that exercises this whole pipeline
end to end - not a real game, just proof the extension point works before
the real games are ported over.

## REST API (v1)

| Method & path | Purpose |
|---|---|
| `GET /api/v1/device` | current board geometry |
| `PUT /api/v1/device` | set `{ "width": .., "height": .. }` |
| `GET /api/v1/ports` | list serial ports visible on the host |
| `POST /api/v1/ports/{role}/assign` | assign a port name to `IN` or `OUT` |
| `GET /api/v1/ports/status` | connection state + assigned ports |
| `POST /api/v1/ports/connect` | open the assigned port(s), start the gateway |
| `POST /api/v1/ports/disconnect` | close the gateway |
| `GET /api/v1/games` | list registered games |
| `GET /api/v1/games/session` | whether a game is currently running |
| `POST /api/v1/games/{gameId}/start` | start a game, body `{ "mode": "EASY" }` |
| `POST /api/v1/games/stop` | stop the running game |
| `GET /api/v1/stream/board` (SSE) | live mirror of every frame sent to the board |

Errors are returned as RFC 7807 `ProblemDetail` JSON with an added
`errorCode` field (e.g. `device_not_configured`, `ports_not_assigned`,
`gateway_not_connected`, `game_not_found`, `no_active_game`).

## What's intentionally deferred

- **Real games** - port `Dooz`, `JumpRow`, `JumpCol`, `ColorMatch`, etc. onto
  `Game<T>`/`GameFactory`, one class pair per game, alongside their own
  scoring/timing rules.
- **Persistence** - `InMemoryDeviceConfigurationService` is the only place
  that would need a repository-backed replacement; ports/games have no
  storage need yet beyond what's already there.
- **Spring Security** - none of the endpoints are protected. Add a
  `SecurityFilterChain` bean once auth requirements are decided; nothing in
  the current design assumes an unauthenticated caller.

## Running it

```bash
mvn -pl tileboard-serial-protocol install    # build & install the library locally
mvn -pl tileboard-app spring-boot:run         # run the app
```

Then, for example:

```bash
curl -X PUT localhost:8080/api/v1/device -H 'Content-Type: application/json' -d '{"width":8,"height":8}'
curl localhost:8080/api/v1/ports
curl -X POST localhost:8080/api/v1/ports/OUT/assign -H 'Content-Type: application/json' -d '{"portName":"COM3"}'
curl -X POST localhost:8080/api/v1/ports/connect
curl localhost:8080/api/v1/games
curl -X POST localhost:8080/api/v1/games/touch-echo/start -H 'Content-Type: application/json' -d '{"mode":"NORMAL"}'
```

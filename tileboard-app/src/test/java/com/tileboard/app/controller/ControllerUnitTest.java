package com.tileboard.app.controller;

import com.tileboard.app.dto.*;
import com.tileboard.app.exception.DeviceNotConfiguredException;
import com.tileboard.app.exception.NoActiveGameException;
import com.tileboard.app.service.device.DeviceConfiguration;
import com.tileboard.app.service.device.DeviceConfigurationService;
import com.tileboard.app.service.serial.*;
import com.tileboard.app.service.streaming.BoardStateBroadcaster;
import com.tileboard.engine.core.*;
import com.tileboard.engine.model.PlayerRole;
import com.tileboard.engine.spring.GameEngineManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControllerUnitTest {

    @Test
    void deviceControllerReadsAndUpdatesConfiguration() {
        DeviceConfigurationService service = mock(DeviceConfigurationService.class);
        DeviceController controller = new DeviceController(service);
        when(service.current()).thenReturn(Optional.empty());
        assertThrows(DeviceNotConfiguredException.class, controller::getCurrentConfiguration);

        DeviceConfiguration config = new DeviceConfiguration(8, 6);
        when(service.configure(8, 6)).thenReturn(config);
        DeviceConfigurationResponse response = controller.configure(new DeviceConfigurationRequest(8, 6));
        assertEquals(48, response.tileCount());
        verify(service).configure(8, 6);
    }

    @Test
    void serialPortControllerDelegatesAllOperationsAndMapsStatus() {
        SerialConnectionManager manager = mock(SerialConnectionManager.class);
        SerialPortController controller = new SerialPortController(manager);
        when(manager.listAvailablePorts()).thenReturn(List.of(new SerialPortSummary("COM1", "USB")));
        when(manager.currentAssignment()).thenReturn(new PortAssignment(Optional.of("COM2"), Optional.of("COM1")));
        when(manager.connectionState()).thenReturn(ConnectionState.CONNECTED);

        assertEquals("COM1", controller.listAvailablePorts().get(0).systemName());
        assertEquals(HttpStatus.NO_CONTENT, controller.assignPort(PortRole.OUT, new AssignPortRequest("COM1")).getStatusCode());
        verify(manager).assign(PortRole.OUT, "COM1");
        assertEquals(ConnectionState.CONNECTED, controller.status().state());
        assertEquals("COM2", controller.status().inPort());
        assertEquals("COM1", controller.status().outPort());

        controller.connect(); verify(manager).connect();
        controller.disconnect(); verify(manager).disconnect();
    }

    @Test
    void gameControllerListsStartsFindsStopsAndHandlesMissingSessions() {
        GameRegistry registry = mock(GameRegistry.class);
        GameEngineManager manager = mock(GameEngineManager.class);
        GameEngine engine = mock(GameEngine.class);
        GameSession session = mock(GameSession.class);
        GameDescriptor descriptor = GameDescriptor.builder("g1", "Game One").build();
        when(registry.listAll()).thenReturn(List.of(descriptor));
        when(manager.require()).thenReturn(engine);
        when(manager.current()).thenReturn(Optional.of(engine));
        when(engine.startGame(eq("g1"), anyList())).thenReturn("s1");
        when(engine.activeSession("s1")).thenReturn(Optional.of(session));
        when(engine.activeSession("missing")).thenReturn(Optional.empty());
        when(engine.activeSessions()).thenReturn(List.of(session));
        when(session.sessionId()).thenReturn("s1");
        when(session.gameId()).thenReturn("g1");
        when(session.status()).thenReturn(GameStatus.RUNNING);

        GameController controller = new GameController(registry, manager);
        assertEquals("g1", controller.listGames().get(0).gameId());
        StartGameRequest request = new StartGameRequest("g1", List.of(new PlayerRequest("Alice", PlayerRole.PLAYER_ONE)));
        assertEquals("s1", controller.startGame(request).getBody().sessionId());
        assertEquals(1, controller.activeSessions().size());
        assertEquals("s1", controller.getSession("s1").sessionId());
        assertEquals(HttpStatus.NO_CONTENT, controller.stopGame("s1").getStatusCode());
        verify(engine).stopGame("s1");
        assertThrows(NoActiveGameException.class, () -> controller.getSession("missing"));
    }

    @Test
    void gameControllerReturnsEmptySessionsWhenEngineIsDisconnected() {
        GameRegistry registry = mock(GameRegistry.class);
        GameEngineManager manager = mock(GameEngineManager.class);
        when(manager.current()).thenReturn(Optional.empty());
        GameController controller = new GameController(registry, manager);
        assertTrue(controller.activeSessions().isEmpty());
        assertThrows(NoActiveGameException.class, () -> controller.getSession("x"));
    }

    @Test
    void streamControllerReturnsBroadcasterSubscription() {
        BoardStateBroadcaster broadcaster = mock(BoardStateBroadcaster.class);
        SseEmitter emitter = new SseEmitter();
        when(broadcaster.subscribe()).thenReturn(emitter);
        assertSame(emitter, new StreamController(broadcaster).streamBoardState());
    }
}

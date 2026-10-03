package com.typerush.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.typerush.game.RoomManager;

/**
 * Hands the client an unused room code so "create room" and "join room" share one code space.
 * The room itself is created when the first player joins with that code.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomManager roomManager;

    public RoomController(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @GetMapping("/new")
    public NewRoom newRoom() {
        return new NewRoom(roomManager.newRoomCode());
    }

    public record NewRoom(String roomCode) {
    }
}
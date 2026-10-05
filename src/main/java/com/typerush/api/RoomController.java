package com.typerush.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.typerush.game.RoomManager;
import com.typerush.security.GamePrincipal;
import com.typerush.security.RateLimiter;

/**
 * Hands the client an unused room code so "create room" and "join room" share one code space.
 * The room itself is created when the first player joins with that code.
 *
 * <p>Creating a room is rate limited per account: codes are five characters from a 32-character
 * alphabet, so an unauthenticated client looping this endpoint could enumerate live rooms.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomManager roomManager;
    private final RateLimiter limiter;

    public RoomController(RoomManager roomManager, RateLimiter limiter) {
        this.roomManager = roomManager;
        this.limiter = limiter;
    }

    @GetMapping("/new")
    public ResponseEntity<NewRoom> newRoom(@AuthenticationPrincipal GamePrincipal principal) {
        if (!limiter.allowRoomCreation(principal.playerId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new NewRoom(null));
        }
        return ResponseEntity.ok(new NewRoom(roomManager.newRoomCode()));
    }

    public record NewRoom(String roomCode) {
    }
}
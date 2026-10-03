package com.typerush.game;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the room phase machine and pushes live progress snapshots to every client.
 * Runs on a single scheduled thread at {@code typerush.game.tick-ms}.
 */
@Component
public class RaceBroadcaster {

    private final RoomManager roomManager;

    public RaceBroadcaster(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Scheduled(fixedDelayString = "${typerush.game.tick-ms:100}")
    public void broadcast() {
        roomManager.tick();
    }
}
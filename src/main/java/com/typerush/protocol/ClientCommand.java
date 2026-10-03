package com.typerush.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Everything a client may send. Unknown/extra fields are ignored on purpose. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientCommand(
        String type,
        String nickname,
        String mode,
        String roomCode,
        Boolean ready,
        Boolean again,
        Integer correctChars,
        Integer errors,
        Integer keystrokes) {
}
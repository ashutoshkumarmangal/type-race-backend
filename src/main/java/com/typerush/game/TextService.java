package com.typerush.game;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import com.typerush.config.GameProperties;
import com.typerush.persistence.TextSnippet;
import com.typerush.persistence.TextSnippetRepository;

@Service
public class TextService {

    /**
     * Keeps a match playable even if the DB seed has not finished yet.
     */
    static final String FALLBACK_TEXT = "The server owns the truth of this race. Every keystroke you send is "
            + "checked against this sentence, measured on a clock that none of the players control, and shared "
            + "with every opponent in the room at the same instant. Progress cannot be faked here, which means "
            + "the number at the end of the race is always worth trusting.";

    private final TextSnippetRepository repository;
    private final GameProperties properties;

    public TextService(TextSnippetRepository repository, GameProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Randomly picks a race text long enough for the configured race length. */
    public TextSnippet pick() {
        int minChars = properties.getGame().getTextLength();
        List<TextSnippet> usable = repository.findUsable(minChars);
        if (usable.isEmpty()) {
            // Fallback text keeps the game playable if the seed has not run yet.
            return new TextSnippet(FALLBACK_TEXT, "fallback");
        }
        return usable.get(ThreadLocalRandom.current().nextInt(usable.size()));
    }
}
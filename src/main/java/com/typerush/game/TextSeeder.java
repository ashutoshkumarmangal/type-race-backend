package com.typerush.game;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import com.typerush.config.GameProperties;
import com.typerush.persistence.TextSnippet;
import com.typerush.persistence.TextSnippetRepository;

/**
 * Loads the built-in race texts on first boot. Snippets are stored in MySQL so they can be
 * edited/replaced without a redeploy; a snippet shorter than the configured race length is padded.
 */
@Configuration
public class TextSeeder {

    private static final Logger log = LoggerFactory.getLogger(TextSeeder.class);

    static final List<String> BUILT_IN_TEXTS = List.of(
            "The best way to predict the future is to invent it. Typing well is a habit built one accurate "
                    + "keystroke at a time, and every race you finish is another rep of that habit. Keep your "
                    + "wrists loose, look a little further ahead than your fingers, and let the rhythm carry "
                    + "you instead of chasing each individual letter.",

            "Realtime systems are a conversation between machines. A browser sends intent, the server decides "
                    + "what is true, and everyone watching sees the same version of reality at the same moment. "
                    + "That agreement is the whole trick: state lives on the server, clients render it, and no "
                    + "player can claim progress they never earned.",

            "Practice makes progress, but only if you practice the right thing. Deliberate practice means "
                    + "choosing a small weakness, giving it your full attention, and repeating until it stops "
                    + "being weak. Type slower on purpose for a minute and your hands will learn the keys faster "
                    + "than any amount of frantic mashing ever could.",

            "There is a particular kind of quiet that arrives when a race is nearly over. Your hands know the "
                    + "text before your eyes do, mistakes thin out, and the words stop feeling like letters and "
                    + "start feeling like sentences. Hold on to that feeling. It is what fluency actually feels "
                    + "like, and it is closer than most people think.",

            "Every race is a small experiment in honesty. The server holds the text, counts the errors and "
                    + "measures the time, so the score on the screen belongs to everyone watching rather than to "
                    + "the fastest hands in the room. When two players finish within a second of each other, the "
                    + "winner is decided by centiseconds and nothing else.",

            "Keys become familiar long before they become easy. The first week of touch typing is a negotiation "
                    + "with eight fingers that have never worked together. Somewhere around the third week, the "
                    + "letters stop being a lookup and start being a reflex, and suddenly you are thinking about "
                    + "the sentence instead of the keyboard.",

            "Speed is a by-product of accuracy, never a substitute for it. A fast typist with poor accuracy "
                    + "spends the extra seconds fixing mistakes, and the correction itself costs more than the "
                    + "error would have. The fastest runs are almost always the calmest ones, where every word "
                    + "lands cleanly the first time.",

            "Progress bars are a kind of promise. They say that somewhere behind the visible edge there is an "
                    + "end, and that the further you go, the less is left. Watching an opponent's bar crawl while "
                    + "your own races ahead is a strange kind of motivation: not anger, just a very clear "
                    + "measurement of the distance between you.",

            "A countdown is a shared breath. Three, two, one, and a room full of strangers begins at the exact "
                    + "same instant because the server decided the start time before any of them could react. "
                    + "That synchronisation is why an online race can feel like everyone is sitting at the same "
                    + "table, even though most of them are thousands of miles away.",

            "Consistency beats intensity. Racing once a day for a month will lift your speed far more than "
                    + "racing six times in one afternoon and then disappearing for a fortnight. The skill is not "
                    + "in the sprint, it is in the habit of returning to the keyboard and doing the same careful "
                    + "work each time you sit down.",

            "Attention has a half-life measured in milliseconds. The moment you stop concentrating on the "
                    + "middle of the line, accuracy falls away faster than speed does, and no amount of speed "
                    + "will save a run that has lost its thread. Look ahead, glance back, and keep the sentence "
                    + "whole in your head.",

            "The database remembers everything you thought it could forget. Every finish time, every personal "
                    + "best, every narrow loss to someone you will never meet is written to a row and waits "
                    + "years later to remind you exactly how far you have come since the first day you sat down "
                    + "and typed a single word on purpose."
    );

    @Bean
    ApplicationRunner seedTexts(TextSnippetRepository repository, GameProperties properties) {
        return args -> seed(repository, properties);
    }

    @Transactional
    void seed(TextSnippetRepository repository, GameProperties properties) {
        if (repository.countByActiveTrue() > 0) {
            log.info("Text snippets already present, skipping seed");
            return;
        }
        int minChars = properties.getGame().getTextLength();
        List<TextSnippet> snippets = BUILT_IN_TEXTS.stream()
                .map(String::trim)
                .filter(t -> t.length() >= minChars)
                .map(t -> new TextSnippet(t, "builtin"))
                .toList();
        repository.saveAll(snippets);
        log.info("Seeded {} race texts (min length {})", snippets.size(), minChars);
    }
}
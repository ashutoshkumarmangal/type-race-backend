package com.typerush.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "text_snippets", indexes = {
        @Index(name = "idx_snippets_active", columnList = "active")
})
public class TextSnippet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 700)
    private String content;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(nullable = false)
    private boolean active;

    public TextSnippet() {
    }

    public TextSnippet(String content, String category) {
        this.content = content;
        this.category = category;
        this.charCount = content.length();
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public String getCategory() {
        return category;
    }

    public int getCharCount() {
        return charCount;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
package com.typerush.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TextSnippetRepository extends JpaRepository<TextSnippet, Long> {

    @Query("SELECT t FROM TextSnippet t WHERE t.active = true AND t.charCount >= :minChars")
    List<TextSnippet> findUsable(int minChars);

    long countByActiveTrue();
}
package com.typerush.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RaceResultRepository extends JpaRepository<RaceResult, Long> {

    @Query("SELECT MIN(r.place) FROM RaceResult r WHERE r.playerId = :playerId AND r.finished = true")
    Optional<Integer> findBestPlaceByPlayerId(@Param("playerId") Long playerId);

    List<RaceResult> findByPlayerIdOrderByCreatedAtDesc(Long playerId, Pageable pageable);

    @Query("""
            SELECT r FROM RaceResult r
            WHERE r.finished = true AND r.flagged = false
            ORDER BY r.wpm DESC, r.accuracy DESC, r.createdAt ASC
            """)
    List<RaceResult> topFinished(Pageable pageable);

    @Query("SELECT COUNT(r) FROM RaceResult r WHERE r.playerId = :playerId AND r.finished = true")
    long countFinishedByPlayer(@Param("playerId") Long playerId);
}
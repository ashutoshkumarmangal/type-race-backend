package com.typerush.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByNicknameKey(String nicknameKey);

Optional<Player> findByUsernameKey(String usernameKey);

boolean existsByUsernameKey(String usernameKey);

boolean existsByNicknameKey(String nicknameKey);

    @Query("""
            SELECT p FROM Player p
            ORDER BY p.bestWpm DESC, p.wins DESC, p.racesPlayed ASC
            """)
    List<Player> topByBestWpm(Pageable pageable);

    @Query("""
            SELECT p FROM Player p
            WHERE p.racesPlayed >= :minRaces
            ORDER BY p.wins DESC, p.bestWpm DESC
            """)
    List<Player> topByWins(Pageable pageable, @Param("minRaces") int minRaces);
}
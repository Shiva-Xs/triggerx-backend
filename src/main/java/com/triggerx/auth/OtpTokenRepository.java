package com.triggerx.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtpTokenRepository extends JpaRepository<OtpToken, UUID> {

    Optional<OtpToken> findFirstByEmailAndInvalidatedAtIsNullOrderByCreatedAtDesc(String email);

    @Query("""
        SELECT COUNT(o) FROM OtpToken o
        WHERE o.email = :email
          AND o.createdAt > :since
        """)
    long countByEmailSince(@Param("email") String email,
                           @Param("since") LocalDateTime since);

    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE OtpToken o
        SET o.invalidatedAt = :now
        WHERE o.email = :email
          AND o.invalidatedAt IS NULL
        """)
    void invalidateAllActiveForEmail(@Param("email") String email,
                                     @Param("now") LocalDateTime now);

}
package io.github.gallileee.lendloop.security;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface EmailChangeTokenRepository extends JpaRepository<EmailChangeToken, Long> {

	// With the user, who is changed after the lookup.
	@Query("select t from EmailChangeToken t join fetch t.user where t.tokenHash = :tokenHash")
	Optional<EmailChangeToken> findByTokenHash(String tokenHash);

	// Only one change is pending at a time (a new request deletes the older ones).
	Optional<EmailChangeToken> findFirstByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(Long userId, Instant now);

	@Modifying
	@Query("delete from EmailChangeToken t where t.user.id = :userId")
	int deleteAllForUser(Long userId);

	@Modifying
	@Query("delete from EmailChangeToken t where t.expiresAt <= :now")
	int deleteExpired(Instant now);
}

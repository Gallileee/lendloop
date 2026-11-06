package io.github.gallileee.lendloop.security;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

	// With the user, who is changed after the lookup.
	@Query("select t from EmailVerificationToken t join fetch t.user where t.tokenHash = :tokenHash")
	Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

	@Modifying
	@Query("delete from EmailVerificationToken t where t.user.id = :userId")
	int deleteAllForUser(Long userId);

	@Modifying
	@Query("delete from EmailVerificationToken t where t.expiresAt <= :now")
	int deleteExpired(Instant now);
}

package io.github.gallileee.lendloop.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByEmail(String email);

	boolean existsByEmail(String email);

	// Checked on every logged-in request, so only the one number is loaded.
	@Query("select u.sessionVersion from AppUser u where u.id = :id")
	Optional<Integer> findSessionVersion(Long id);

	// For the "confirm your email" banner on every page.
	boolean existsByIdAndEmailVerifiedAtIsNull(Long id);
}

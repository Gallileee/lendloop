package io.github.gallileee.lendloop.user;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	Optional<AppUser> findByEmail(String email);

	boolean existsByEmail(String email);

	// Checked on every logged-in request, so only the one number is loaded.
	@Query("select u.sessionVersion from AppUser u where u.id = :id")
	Optional<Integer> findSessionVersion(Long id);

	// For the "confirm your email" banner on every page.
	boolean existsByIdAndEmailVerifiedAtIsNull(Long id);

	// Whether to show book covers to this user (Settings, off by default).
	boolean existsByIdAndShowBookCoversTrue(Long id);

	// An account UnconfirmedAccountCleanup may delete: the address was never confirmed, and the
	// account holds nothing anyone would miss (no items, no groups, never borrowed anything).
	String UNUSED_AND_UNCONFIRMED = """
			u.emailVerifiedAt is null
			and not exists (select 1 from Item i where i.owner = u)
			and not exists (select 1 from GroupMember m where m.user = u)
			and not exists (select 1 from Loan l where l.borrower = u)""";

	// Not warned yet, and created at or before `createdBy`.
	@Query("select u.id from AppUser u where u.deletionWarnedAt is null and u.createdAt <= :createdBy and "
			+ UNUSED_AND_UNCONFIRMED + " order by u.id")
	List<Long> findUnusedToWarn(Instant createdBy);

	// Created at or before `createdBy` and warned at or before `warnedBy`.
	@Query("select u.id from AppUser u where u.createdAt <= :createdBy and u.deletionWarnedAt <= :warnedBy and "
			+ UNUSED_AND_UNCONFIRMED + " order by u.id")
	List<Long> findUnusedToDelete(Instant createdBy, Instant warnedBy);

	// Checks the conditions again in the same statement, so an account that was confirmed or got
	// an item since findUnusedToDelete stays. Tokens go with it (on delete cascade).
	@Modifying
	@Query("delete from AppUser u where u.id = :id and u.deletionWarnedAt is not null and "
			+ UNUSED_AND_UNCONFIRMED)
	int deleteIfUnused(Long id);

	// A warned account that was confirmed or started being used is no longer going to be deleted.
	// If it becomes unused again later, it gets a new warning before anything happens.
	@Modifying
	@Query("update AppUser u set u.deletionWarnedAt = null where u.deletionWarnedAt is not null and not ("
			+ UNUSED_AND_UNCONFIRMED + ")")
	int forgetWarningsOfUsedAccounts();
}

package io.github.gallileee.lendloop.group;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {

	// The user's memberships with their groups, for the "My groups" list.
	@Query("select m from GroupMember m join fetch m.group g where m.user.id = :userId order by lower(g.name)")
	List<GroupMember> findByUserIdWithGroup(Long userId);

	@Query("select m from GroupMember m join fetch m.user u where m.group.id = :groupId order by lower(u.displayName)")
	List<GroupMember> findByGroupIdWithUser(Long groupId);

	// Fetches the group too: pages read it after the transaction ends (open-in-view is off).
	@Query("select m from GroupMember m join fetch m.group where m.group.id = :groupId and m.user.id = :userId")
	Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

	boolean existsByGroupIdAndUserId(Long groupId, Long userId);

	long countByGroupId(Long groupId);
}

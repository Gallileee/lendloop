package io.github.gallileee.lendloop.group;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	@Query("select m from GroupMember m join fetch m.user where m.group.id = :groupId and m.user.id = :userId")
	Optional<GroupMember> findByGroupIdAndUserIdWithUser(Long groupId, Long userId);

	boolean existsByGroupIdAndUserId(Long groupId, Long userId);

	long countByGroupId(Long groupId);

	// One statement instead of loading every membership; used before deleting the group itself.
	// Clears the persistence context afterwards: memberships loaded earlier in the transaction
	// would otherwise still point at the group and stop Hibernate from deleting it.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from GroupMember m where m.group.id = :groupId")
	int deleteByGroupId(Long groupId);

	// Whether two users are together in at least one group (who may borrow from whom).
	@Query("""
			select count(a) > 0 from GroupMember a, GroupMember b
			where a.group.id = b.group.id and a.user.id = :userId and b.user.id = :otherUserId""")
	boolean shareAGroup(Long userId, Long otherUserId);
}

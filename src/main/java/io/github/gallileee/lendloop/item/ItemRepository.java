package io.github.gallileee.lendloop.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ItemRepository extends JpaRepository<Item, Long> {

	List<Item> findByOwnerIdOrderByNameAsc(Long ownerId);

	// Looks an item up only among the owner's items, so another user's id behaves like a missing one.
	Optional<Item> findByIdAndOwnerId(Long id, Long ownerId);

	// What a group can borrow: every member's items that are marked as lendable.
	@Query("""
			select i from Item i join fetch i.owner o
			where i.available = true
			  and o.id in (select m.user.id from GroupMember m where m.group.id = :groupId)
			order by lower(i.name), i.id""")
	List<Item> findLendableInGroup(Long groupId);
}

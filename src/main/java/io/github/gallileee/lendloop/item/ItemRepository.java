package io.github.gallileee.lendloop.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

	List<Item> findByOwnerIdOrderByNameAsc(Long ownerId);

	// Looks an item up only among the owner's items, so another user's id behaves like a missing one.
	Optional<Item> findByIdAndOwnerId(Long id, Long ownerId);
}

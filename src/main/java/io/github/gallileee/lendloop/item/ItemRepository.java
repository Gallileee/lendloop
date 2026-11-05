package io.github.gallileee.lendloop.item;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {

	List<Item> findByOwnerIdOrderByNameAsc(Long ownerId);

	// Looks an item up only among the owner's items, so another user's id behaves like a missing one.
	Optional<Item> findByIdAndOwnerId(Long id, Long ownerId);

	// With the owner loaded, for callers that show the owner's name after the transaction.
	@Query("select i from Item i join fetch i.owner where i.id = :id")
	Optional<Item> findWithOwnerById(Long id);

	@Query("select count(l) > 0 from Loan l where l.item.id = :itemId and l.status = 'ACTIVE'")
	boolean isLentOut(Long itemId);
}

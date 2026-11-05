package io.github.gallileee.lendloop.item;

import io.github.gallileee.lendloop.book.Isbn;
import io.github.gallileee.lendloop.user.AppUserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Every method takes the owner's id and only ever touches that owner's items.
@Service
public class ItemService {

	private final ItemRepository items;
	private final AppUserRepository users;

	public ItemService(ItemRepository items, AppUserRepository users) {
		this.items = items;
		this.users = users;
	}

	@Transactional(readOnly = true)
	public List<Item> listOwnedBy(Long ownerId) {
		return items.findByOwnerIdOrderByNameAsc(ownerId);
	}

	@Transactional(readOnly = true)
	public Item getOwned(Long id, Long ownerId) {
		return items.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new ItemNotFoundException(id));
	}

	@Transactional
	public Item create(Long ownerId, ItemForm form) {
		Item item = new Item(users.getReferenceById(ownerId), form.getName().trim(), blankToNull(form.getDescription()),
				form.getCategory(), form.isAvailable());
		item.setIsbn(Isbn.parse(form.getIsbn()).orElse(null));
		return items.save(item);
	}

	@Transactional
	public Item update(Long id, Long ownerId, ItemForm form) {
		Item item = getOwned(id, ownerId);
		item.update(form.getName().trim(), blankToNull(form.getDescription()), form.getCategory(), form.isAvailable());
		item.setIsbn(Isbn.parse(form.getIsbn()).orElse(null));
		return item;
	}

	// Deleting would also delete its loans (on delete cascade), so a lent-out item has to be
	// marked as returned first. Open requests for it are simply dropped.
	@Transactional
	public void delete(Long id, Long ownerId) {
		Item item = getOwned(id, ownerId);
		if (items.isLentOut(id)) {
			throw new ItemLentOutException();
		}
		items.delete(item);
	}

	public static class ItemLentOutException extends RuntimeException {

		ItemLentOutException() {
			super("A lent-out item can't be deleted");
		}
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}
}

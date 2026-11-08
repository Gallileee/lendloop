package io.github.gallileee.lendloop.item;

import io.github.gallileee.lendloop.book.BookLookup;
import io.github.gallileee.lendloop.book.BookLookup.BookLookupUnavailableException;
import io.github.gallileee.lendloop.book.Isbn;
import io.github.gallileee.lendloop.loan.LoanService;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.web.Dates;
import io.github.gallileee.lendloop.web.Messages;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// The current user's own items. Writes redirect afterwards (post/redirect/get),
// so reloading the page never submits a form twice.
@Controller
@RequestMapping("/items")
public class ItemController {

	private static final Logger log = LoggerFactory.getLogger(ItemController.class);

	// Longer than any ISBN with hyphens; longer input isn't looked up or echoed back.
	private static final int MAX_ISBN_INPUT = 30;

	private final ItemService itemService;
	private final LoanService loanService;
	private final BookLookup bookLookup;
	private final Dates dates;
	private final Messages messages;

	public ItemController(ItemService itemService, LoanService loanService, BookLookup bookLookup, Dates dates,
			Messages messages) {
		this.itemService = itemService;
		this.loanService = loanService;
		this.bookLookup = bookLookup;
		this.dates = dates;
		this.messages = messages;
	}

	@ModelAttribute("categories")
	Category[] categories() {
		return Category.values();
	}

	@GetMapping
	String list(@AuthenticationPrincipal AppUserDetails me, Model model) {
		List<Item> items = itemService.listOwnedBy(me.id());
		model.addAttribute("items", items);
		// Shows "Lent to Sam until …" next to the things that are out.
		model.addAttribute("loans", loanService.stateFor(items, me.id()));
		model.addAttribute("today", dates.today());
		// For the "Lend to someone" form on items that are at home.
		model.addAttribute("dueDates", loanService.dueDateRange());
		model.addAttribute("outsiderNames", loanService.outsiderNamesOf(me.id()));
		return "items/list";
	}

	// With ?isbn=… (the "Look it up" box) the form comes back filled in from Open Library.
	// A GET, because it only reads: nothing is saved until the user submits the form below it.
	@GetMapping("/new")
	String newForm(@RequestParam(required = false) String isbn, Model model) {
		ItemForm form = new ItemForm();
		model.addAttribute("form", form);
		if (isbn != null && !isbn.isBlank()) {
			lookUp(isbn.strip(), form, model);
		}
		return "items/form";
	}

	private void lookUp(String typed, ItemForm form, Model model) {
		if (typed.length() > MAX_ISBN_INPUT) {
			model.addAttribute("lookupError", "item.lookup.invalid");
			return;
		}
		// Shown in the box again, so a typo can be fixed.
		model.addAttribute("isbnQuery", typed);
		Optional<Isbn> isbn = Isbn.parse(typed);
		if (isbn.isEmpty()) {
			model.addAttribute("lookupError", "item.lookup.invalid");
			return;
		}
		form.setCategory(Category.BOOK);
		form.setIsbn(isbn.get().value());
		try {
			bookLookup.find(isbn.get()).ifPresentOrElse(book -> {
				form.setName(book.itemName());
				form.setDescription(book.itemDescription());
				model.addAttribute("lookupFound", "item.lookup.found");
			}, () -> model.addAttribute("lookupError",
					"item.lookup.notFound"));
		}
		catch (BookLookupUnavailableException e) {
			log.warn("{}: {}", e.getMessage(), String.valueOf(e.getCause()));
			model.addAttribute("lookupError", "item.lookup.unavailable");
		}
	}

	@PostMapping
	String create(@AuthenticationPrincipal AppUserDetails me, @Valid @ModelAttribute("form") ItemForm form,
			BindingResult result, RedirectAttributes redirect) {
		if (result.hasErrors()) {
			return "items/form";
		}
		Item item = itemService.create(me.id(), form);
		redirect.addFlashAttribute("message", messages.text("item.flash.added", item.getName()));
		return "redirect:/items";
	}

	@GetMapping("/{id}/edit")
	String editForm(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id, Model model) {
		model.addAttribute("itemId", id);
		model.addAttribute("form", ItemForm.from(itemService.getOwned(id, me.id())));
		return "items/form";
	}

	@PostMapping("/{id}")
	String update(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
			@Valid @ModelAttribute("form") ItemForm form, BindingResult result, Model model,
			RedirectAttributes redirect) {
		if (result.hasErrors()) {
			model.addAttribute("itemId", id);
			return "items/form";
		}
		Item item = itemService.update(id, me.id(), form);
		redirect.addFlashAttribute("message", messages.text("item.flash.saved", item.getName()));
		return "redirect:/items";
	}

	@PostMapping("/{id}/delete")
	String delete(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id, RedirectAttributes redirect) {
		try {
			itemService.delete(id, me.id());
		}
		catch (ItemService.ItemLentOutException e) {
			redirect.addFlashAttribute("error", messages.text("item.flash.lentOut"));
			return "redirect:/items";
		}
		redirect.addFlashAttribute("message", messages.text("item.flash.deleted"));
		return "redirect:/items";
	}
}

package io.github.gallileee.lendloop.item;

import io.github.gallileee.lendloop.loan.LoanService;
import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.web.Dates;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// The current user's own items. Writes redirect afterwards (post/redirect/get),
// so reloading the page never submits a form twice.
@Controller
@RequestMapping("/items")
public class ItemController {

	private final ItemService itemService;
	private final LoanService loanService;
	private final Dates dates;

	public ItemController(ItemService itemService, LoanService loanService, Dates dates) {
		this.itemService = itemService;
		this.loanService = loanService;
		this.dates = dates;
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
		return "items/list";
	}

	@GetMapping("/new")
	String newForm(Model model) {
		model.addAttribute("form", new ItemForm());
		return "items/form";
	}

	@PostMapping
	String create(@AuthenticationPrincipal AppUserDetails me, @Valid @ModelAttribute("form") ItemForm form,
			BindingResult result, RedirectAttributes redirect) {
		if (result.hasErrors()) {
			return "items/form";
		}
		Item item = itemService.create(me.id(), form);
		redirect.addFlashAttribute("message", "Added “" + item.getName() + "”.");
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
		redirect.addFlashAttribute("message", "Saved “" + item.getName() + "”.");
		return "redirect:/items";
	}

	@PostMapping("/{id}/delete")
	String delete(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id, RedirectAttributes redirect) {
		try {
			itemService.delete(id, me.id());
		}
		catch (ItemService.ItemLentOutException e) {
			redirect.addFlashAttribute("error", "It's lent out right now. Mark it as returned under Loans first.");
			return "redirect:/items";
		}
		redirect.addFlashAttribute("message", "Item deleted.");
		return "redirect:/items";
	}
}

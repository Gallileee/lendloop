package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.web.Dates;
import io.github.gallileee.lendloop.web.Messages;
import java.time.LocalDate;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Every action redirects back to the loans page (or the group it came from) with a message,
// so a reload never repeats it.
@Controller
@RequestMapping("/loans")
public class LoanController {

	private final LoanService loanService;
	private final Dates dates;
	private final Messages messages;

	public LoanController(LoanService loanService, Dates dates, Messages messages) {
		this.loanService = loanService;
		this.dates = dates;
		this.messages = messages;
	}

	@GetMapping
	String overview(@AuthenticationPrincipal AppUserDetails me, Model model) {
		model.addAttribute("overview", loanService.overview(me.id()));
		model.addAttribute("today", dates.today());
		model.addAttribute("dueDates", loanService.dueDateRange());
		return "loans/list";
	}

	@PostMapping
	String request(@AuthenticationPrincipal AppUserDetails me, @RequestParam Long itemId,
			@RequestParam(required = false) String message, @RequestParam(required = false) Long groupId,
			RedirectAttributes redirect) {
		String back = groupId == null ? "redirect:/loans" : "redirect:/groups/" + groupId;
		return run(redirect, back, "loan.rule.alreadyAsked", () -> {
			Loan loan = loanService.request(itemId, me.id(), message);
			return messages.text("loan.flash.asked", loan.getItem().getOwner().getDisplayName(), loan.getItem().getName());
		});
	}

	@PostMapping("/{id}/approve")
	String approve(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
			RedirectAttributes redirect) {
		return run(redirect, "redirect:/loans", "loan.rule.alreadyLent", () -> lent(loanService.approve(id, me.id(), dueDate)));
	}

	// From the My things page: the owner lends an item to someone who isn't on Lendloop.
	@PostMapping("/outside")
	String lendToOutsider(@AuthenticationPrincipal AppUserDetails me, @RequestParam Long itemId,
			@RequestParam(required = false) String borrowerName,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
			RedirectAttributes redirect) {
		return run(redirect, "redirect:/items", "loan.rule.alreadyLent",
				() -> lent(loanService.lendToOutsider(itemId, me.id(), borrowerName, dueDate)));
	}

	@PostMapping("/{id}/decline")
	String decline(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id, RedirectAttributes redirect) {
		return run(redirect, "redirect:/loans", null, () -> {
			Loan loan = loanService.decline(id, me.id());
			return messages.text("loan.flash.declined", loan.getBorrower().getDisplayName());
		});
	}

	@PostMapping("/{id}/cancel")
	String cancel(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id, RedirectAttributes redirect) {
		return run(redirect, "redirect:/loans", null, () -> {
			Loan loan = loanService.cancel(id, me.id());
			return messages.text("loan.flash.withdrew", loan.getItem().getName());
		});
	}

	@PostMapping("/{id}/return")
	String markReturned(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
			RedirectAttributes redirect) {
		return run(redirect, "redirect:/loans", null, () -> {
			Loan loan = loanService.markReturned(id, me.id());
			return messages.text("loan.flash.returned", loan.getItem().getName());
		});
	}

	private String lent(Loan loan) {
		return messages.text("loan.flash.lent", loan.getItem().getName(), loan.getBorrowerDisplayName(),
				dates.local(loan.getDueDate()));
	}

	// Runs one action and turns its outcome into a flash message. A unique-constraint violation
	// means another request won a race (see LoanService); it's reported with the raceCode text.
	private String run(RedirectAttributes redirect, String back, String raceCode, Supplier<String> action) {
		try {
			redirect.addFlashAttribute("message", action.get());
		}
		catch (LoanRuleException e) {
			redirect.addFlashAttribute("error", messages.text(e));
		}
		catch (DataIntegrityViolationException e) {
			if (raceCode == null) {
				throw e;
			}
			redirect.addFlashAttribute("error", messages.text(raceCode));
		}
		return back;
	}
}

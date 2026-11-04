package io.github.gallileee.lendloop.loan;

import io.github.gallileee.lendloop.security.AppUserDetails;
import io.github.gallileee.lendloop.web.Dates;
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

	public LoanController(LoanService loanService, Dates dates) {
		this.loanService = loanService;
		this.dates = dates;
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
		return run(redirect, back, "You've already asked for this.", () -> {
			Loan loan = loanService.request(itemId, me.id(), message);
			return "Asked " + loan.getItem().getOwner().getDisplayName() + " for “" + loan.getItem().getName()
					+ "”. You'll see the answer under Loans.";
		});
	}

	@PostMapping("/{id}/approve")
	String approve(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
			RedirectAttributes redirect) {
		return run(redirect, "redirect:/loans", "It's already lent out. Mark it as returned first.", () -> {
			Loan loan = loanService.approve(id, me.id(), dueDate);
			return "Lent “" + loan.getItem().getName() + "” to " + loan.getBorrowerDisplayName()
					+ " until " + dates.format(loan.getDueDate()) + ".";
		});
	}

	// From the My things page: the owner lends an item to someone who isn't on Lendloop.
	@PostMapping("/outside")
	String lendToOutsider(@AuthenticationPrincipal AppUserDetails me, @RequestParam Long itemId,
			@RequestParam(required = false) String borrowerName,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
			RedirectAttributes redirect) {
		return run(redirect, "redirect:/items", "It's already lent out. Mark it as returned first.", () -> {
			Loan loan = loanService.lendToOutsider(itemId, me.id(), borrowerName, dueDate);
			return "Lent “" + loan.getItem().getName() + "” to " + loan.getBorrowerDisplayName()
					+ " until " + dates.format(loan.getDueDate()) + ".";
		});
	}

	@PostMapping("/{id}/decline")
	String decline(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id, RedirectAttributes redirect) {
		return run(redirect, "redirect:/loans", null, () -> {
			Loan loan = loanService.decline(id, me.id());
			return "Declined " + loan.getBorrower().getDisplayName() + "’s request.";
		});
	}

	@PostMapping("/{id}/cancel")
	String cancel(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id, RedirectAttributes redirect) {
		return run(redirect, "redirect:/loans", null, () -> {
			Loan loan = loanService.cancel(id, me.id());
			return "Withdrew your request for “" + loan.getItem().getName() + "”.";
		});
	}

	@PostMapping("/{id}/return")
	String markReturned(@AuthenticationPrincipal AppUserDetails me, @PathVariable Long id,
			RedirectAttributes redirect) {
		return run(redirect, "redirect:/loans", null, () -> {
			Loan loan = loanService.markReturned(id, me.id());
			return "“" + loan.getItem().getName() + "” is back. Thanks for lending it!";
		});
	}

	// Runs one action and turns its outcome into a flash message. A unique-constraint violation
	// means another request won a race (see LoanService); it's reported with raceMessage.
	private static String run(RedirectAttributes redirect, String back, String raceMessage, Supplier<String> action) {
		try {
			redirect.addFlashAttribute("message", action.get());
		}
		catch (LoanRuleException e) {
			redirect.addFlashAttribute("error", e.getMessage());
		}
		catch (DataIntegrityViolationException e) {
			if (raceMessage == null) {
				throw e;
			}
			redirect.addFlashAttribute("error", raceMessage);
		}
		return back;
	}
}

package io.github.gallileee.lendloop.group;

import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.loan.Loan;
import io.github.gallileee.lendloop.loan.LoanStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

// The group page's item query, built from only the parts of the filter that are set, so an
// empty search box adds no "or :q is null" condition (those untyped null parameters are what
// PostgreSQL tends to reject). Lives in the group package because it needs both Item and Loan,
// and the loan package already depends on the item package.
final class GroupItemSpecs {

	private GroupItemSpecs() {
	}

	// What a group can borrow: every member's items that are marked as lendable.
	static Specification<Item> lendableInGroup(Long groupId) {
		return (item, query, cb) -> {
			Subquery<Long> memberIds = query.subquery(Long.class);
			Root<GroupMember> member = memberIds.from(GroupMember.class);
			memberIds.select(member.get("user").get("id")).where(cb.equal(member.get("group").get("id"), groupId));
			return cb.and(cb.isTrue(item.get("available")), item.get("owner").get("id").in(memberIds));
		};
	}

	// The owner is shown next to each item after the transaction (open-in-view is off), so it's
	// fetched in the same query. Not for count queries, where a fetch join is an error.
	static Specification<Item> withOwner() {
		return (item, query, cb) -> {
			if (query.getResultType() != Long.class && query.getResultType() != long.class) {
				item.fetch("owner");
			}
			return null;
		};
	}

	static Specification<Item> matching(ItemFilter filter) {
		return (item, query, cb) -> {
			List<Predicate> parts = new ArrayList<>();
			if (!filter.text().isEmpty()) {
				parts.add(textMatches(item, cb, filter));
			}
			if (filter.category() != null) {
				parts.add(cb.equal(item.get("category"), filter.category()));
			}
			if (filter.availableNow()) {
				Subquery<Long> activeLoans = query.subquery(Long.class);
				Root<Loan> loan = activeLoans.from(Loan.class);
				activeLoans.select(loan.get("id"))
					.where(cb.equal(loan.get("item"), item), cb.equal(loan.get("status"), LoanStatus.ACTIVE));
				parts.add(cb.not(cb.exists(activeLoans)));
			}
			return parts.isEmpty() ? null : cb.and(parts.toArray(Predicate[]::new));
		};
	}

	// Name or description contains the text, ignoring case and accents (both sides are folded by
	// SearchText; the item's side is stored in search_text). A typed ISBN also finds the book by
	// its stored ISBN-13, whatever format it was typed in.
	private static Predicate textMatches(Root<Item> item, CriteriaBuilder cb, ItemFilter filter) {
		String pattern = filter.likePattern();
		List<Predicate> any = new ArrayList<>();
		any.add(cb.like(item.get("searchText"), pattern, ItemFilter.ESCAPE_CHAR));
		filter.isbn().ifPresent(isbn -> any.add(cb.equal(item.get("isbn"), isbn.value())));
		return cb.or(any.toArray(Predicate[]::new));
	}
}

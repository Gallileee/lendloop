package io.github.gallileee.lendloop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gallileee.lendloop.group.AppGroup;
import io.github.gallileee.lendloop.group.GroupForm;
import io.github.gallileee.lendloop.group.GroupService;
import io.github.gallileee.lendloop.item.Category;
import io.github.gallileee.lendloop.item.Item;
import io.github.gallileee.lendloop.item.ItemForm;
import io.github.gallileee.lendloop.item.ItemService;
import io.github.gallileee.lendloop.user.AppUser;
import io.github.gallileee.lendloop.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

// The other tests run on H2. This one runs the Flyway migrations and a round trip on real
// PostgreSQL, so SQL that only H2 accepts is caught. Skipped where Docker isn't available;
// CI (GitHub Actions) has Docker, so it always runs there.
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostgresMigrationTest {

	@Autowired
	AppUserRepository users;

	@Autowired
	ItemService itemService;

	@Autowired
	GroupService groupService;

	@Test
	void groupQueriesWorkOnPostgres() {
		AppUser owner = users.save(new AppUser("pg-owner@example.com", "Owner", "{noop}irrelevant"));
		AppUser friend = users.save(new AppUser("pg-friend@example.com", "Friend", "{noop}irrelevant"));
		GroupForm groupForm = new GroupForm();
		groupForm.setName("Postgres club");
		AppGroup group = groupService.create(owner.getId(), groupForm);
		groupService.join(group.getInviteToken(), friend.getId());
		ItemForm itemForm = new ItemForm();
		itemForm.setName("Tent");
		itemForm.setCategory(Category.OUTDOOR);
		itemService.create(friend.getId(), itemForm);

		GroupService.GroupPage page = groupService.page(group.getId(), owner.getId());
		assertThat(page.members()).extracting(m -> m.getUser().getDisplayName()).containsExactly("Friend", "Owner");
		assertThat(page.lendableItems()).extracting(Item::getName).containsExactly("Tent");
		assertThat(groupService.membershipsOf(friend.getId())).hasSize(1);
	}

	@Test
	void migrationsMatchTheEntitiesAndDataRoundTrips() {
		AppUser owner = users.save(new AppUser("pg@example.com", "Postgres", "{noop}irrelevant"));
		ItemForm form = new ItemForm();
		form.setName("Cordless drill");
		form.setCategory(Category.TOOL);
		itemService.create(owner.getId(), form);

		assertThat(itemService.listOwnedBy(owner.getId()))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.getName()).isEqualTo("Cordless drill");
				assertThat(item.getCategory()).isEqualTo(Category.TOOL);
				assertThat(item.isAvailable()).isTrue();
			});
	}
}

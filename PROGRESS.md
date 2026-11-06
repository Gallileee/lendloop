# Progress log

## 2026-11-06: Milestone 3h, failed-login limits

Why: next step 1, picked by the owner. Without a limit, a script could try passwords on an account as fast as BCrypt allows. OWASP's authentication cheat sheet recommends throttling failed logins per account. Unlike a permanent lockout, a short pause can't be abused to lock someone out for good.

Done:
- `LoginThrottle` (security package): a sliding window of failed logins, in memory.
  - Keys `email:<lower-cased>` (5 per 15 min) and `ip:<address>` (30 per 15 min), configurable (`LOGIN_*`).
  - Only the last `max` failure times are kept per key. Blocked while the oldest of them is inside the window; `blockedFor` returns the remaining wait.
  - Failures while blocked aren't counted, so a pause always ends. A success clears the email but not the IP, so logging into your own account between guesses doesn't reset it.
  - Memory: at most 100,000 keys and emails cut to 320 characters, plus `@Scheduled` purging every 10 min.
- `LoginThrottleFilter` before `UsernamePasswordAuthenticationFilter` (so after the CSRF check): a blocked `POST /login` goes to `/login?blocked` without the password being checked. It's not a `@Component`, because Spring Boot would also register it as a plain servlet filter.
- `SecurityConfig`: counting success and failure handlers.
  - The success handler still goes back to the saved page, else `/items`, like `defaultSuccessUrl` did.
  - The attempt that reaches the limit already shows the pause message.
- Login page: "Too many failed attempts… paused for up to 15 minutes".
- `WebTest` clears the throttle after each test (MockMvc requests share one IP).
- Tests: `LoginThrottleTest` (8, movable clock) and `LoginRateLimitTests` (6). 182 in total.

**Bug found by the tests:** the filter first matched `request.getServletPath()`. That's empty in MockMvc, and in general it depends on how the servlet is mapped, so the filter never ran. It now compares the request URI without the context path.

Verified locally:
- `./mvnw test`: 182 run, 0 failures, 3 skipped (PostgreSQL, no Docker; CI runs them).
- In the browser (`lendloop-memory-demo`): through the real form, 4 wrong passwords gave "Wrong email or password" and the 5th gave the pause message. Then the right password was also refused with the pause message, and the log shows "Refused a login from 127.0.0.1 … (allowed again in 893 s)". No server errors.

Trade-offs:
- In memory instead of the database or Redis: no migration and no new dependency (Bucket4j would add one). It's forgotten on restart and assumes one instance, like the reminder job.
- Per-account limits let someone pause another person's logins for 15 minutes. That's accepted: the pause is short, and a CAPTCHA would be heavier than this app needs.
- Behind a proxy the IP rule needs `server.forward-headers-strategy`, or all users share one IP and its limit of 30.

## 2026-11-06: Milestone 3g, one-click unsubscribe (RFC 8058)

Why: next step 1, picked by the owner. Gmail and Yahoo have required one-click unsubscribe from bulk senders since 2024, and before this, turning emails off needed a login.

Done:
- `EmailList` enum (`BORROWED`, `LENT`), one per switch on the Settings page. `AppUser.turnOff(list)` and `wants(list)`.
- `UnsubscribeLinks`: `/unsubscribe?user=…&list=…&sig=…`, where `sig` is HMAC-SHA256 over `unsubscribe:<id>:<list>` (base64url).
  - Checked with `MessageDigest.isEqual` (constant time). Malformed parameters give "invalid", never an exception.
  - Nothing stored, no expiry: the worst a leaked link can do is turn one email off, and the user can turn it back on.
  - Secret: `lendloop.unsubscribe.secret` / `UNSUBSCRIBE_SECRET`, at least 32 characters, else startup fails. Not set: a random one per run plus a warning (local runs).
- `UnsubscribeController`:
  - GET shows "Stop these emails?" with a POST button. Mail scanners open links, so a GET changes nothing.
  - POST turns the list off. It's what the button and the mail provider's one-click POST (`List-Unsubscribe=One-Click`) send. It's idempotent.
  - Bad link or deleted account: the same 404 page.
- `SecurityConfig`: `/unsubscribe` is public and the only path without CSRF, because the provider's POST has no cookies or token. The signature does that job.
- `ReminderMailer` now sends a `MimeMessage` (a `SimpleMailMessage` can't carry extra headers), with `List-Unsubscribe: <link>` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click`. The footer says which kind of email this is, gives the same link and the Settings link. Borrower reminders link to `BORROWED`, owner notices and reminders to `LENT`.
- Tests: `UnsubscribeLinksTest` (15, no Spring), `UnsubscribeTests` (6), `LoanReminderTests` now captures MIME messages and checks both headers and that the borrower and owner get different lists. 168 in total.

Verified locally:
- `./mvnw test`: 168 run, 0 failures, 3 skipped (PostgreSQL, no Docker; CI runs them).
- Mutation check: without the CSRF exception for `/unsubscribe`, 4 of the 6 `UnsubscribeTests` fail. Restored afterwards.
- In the browser (`lendloop-memory-demo`, which now sets a demo-only secret so a link can be computed by hand):
  - Logged out, a computed link showed "Stop these emails?" and *Unsubscribe* turned off borrow reminders.
  - A curl POST with `List-Unsubscribe=One-Click` and no cookies turned off the lent-out notices (200). The same signature with another user id got 404.
  - After logging in, Settings showed both boxes unticked. No server errors.

Not verified: a real mail client's Unsubscribe button. That needs a real domain with DKIM.

Trade-offs:
- Stateless signed links over a token table: no migration and nothing to clean up. The price is no per-link revocation (only rotating the secret).
- The user id is visible in the link. It isn't secret, and the signature is what authorises.

## 2026-11-05: Milestone 3f, search and filter on the group page

Why: next step 1 in this log. Once a group has more than a screenful of things, scrolling is the only way to find the drill. Every lending / library app has a search box and category filter, and "is it free now?" is the first question before asking.

Done:
- `ItemFilter` record (group package): text, category, "free right now", parsed leniently from raw query parameters. An unknown category or `available=yes` is dropped, not a 400 on a GET. Text is trimmed, whitespace collapsed, cut to 100 characters.
- `GroupItemSpecs`: the group page's query is now built with **Spring Data JPA Specifications** (Criteria API) instead of the fixed `ItemRepository.findLendableInGroup` JPQL, which is removed.
  - Only the parts of the filter that are set become predicates. The alternative, one JPQL query with `(:q is null or …)`, binds untyped nulls, which PostgreSQL often rejects.
  - Text: `lower(name) like` or `lower(description) like` (author is in the description for books). If the text is a valid ISBN (any format), also `isbn = <ISBN-13>`.
  - **`%`, `_` and `\` from the user are escaped**, and the LIKE gets an explicit escape character. Without the explicit `escape`, newer Hibernate versions emit `escape ''` on PostgreSQL, which would make the backslash escaping useless.
  - "Free right now": `not exists` an `ACTIVE` loan for the item.
  - The owner is fetch-joined (open-in-view is off), skipped for the count query.
  - Order: name ignoring case, then id (a `Sort` with `ignoreCase()`).
- `GroupService.page(groupId, userId, filter)`: `GroupPage` now carries the filter, the matching `items` and `lendableCount` (one extra count query only when a filter is active).
- Group page: a GET search form (`role="search"`, labelled fields, values kept), "2 of 9" in the count, *Clear*, and "Nothing matches your search" separate from "Nobody has anything to lend yet". One column on phones.
- Tests: `ItemFilterTest` (13, no Spring), `GroupSearchTests` (10, `WebTest`), `PostgresMigrationTest` checks the escaped LIKE, the ISBN branch and `not exists` on PostgreSQL. 147 in total.

Verified locally:
- `./mvnw test`: 147 run, 0 failures, 3 skipped (PostgreSQL, no Docker). CI: 147 run, 0 skipped.
- Mutation check: removing the `%` escaping makes `GroupSearchTests.wildcardsInTheSearchAreTakenLiterally` fail. Restored afterwards.

Not verified locally: the form in a browser (starting the demo server was declined in this automated run). To check by hand: start the `lendloop-memory-demo` launch config (port 8093), register two accounts, put a few items in a group, then try a search, a category, "Free right now" and the layout at 375 px and around 600 px wide (four columns in one row on desktop).

Trade-offs:
- Substring LIKE over full-text search: no extra infrastructure and works on H2 and PostgreSQL alike; no ranking or typo tolerance, and no index use. A group holds tens to hundreds of things.
- No pagination: same reasoning. `JpaSpecificationExecutor.findAll(spec, pageable)` makes it a small change later.
- Book covers from `covers.openlibrary.org` left out: they'd reveal every viewer's IP address to Open Library, so they need an opt-in setting first.

## 2026-11-05: Milestone 3e, owner reminders for loans outside the app

Why: next step 1, chosen by the owner. A borrower outside the app has no email address, so after 3c nobody got the "due tomorrow" reminder or the weekly overdue ones for these loans. The owner only got one overdue notice.

Done:
- `LoanReminderJob`: for a loan to someone outside the app, the owner gets the borrower's schedule (`Loan.reminderDueOn`): the day before, then the first overdue day and weekly. One email per day, not a reminder plus the one-off owner notice.
  - A failed email is not recorded and is retried next run. The owner is the only recipient, so a retry can't remind anyone twice (unlike in-app loans, where the owner notice isn't retried).
  - Governed by the existing "notices about things I lent out" switch (`notify_lent_overdue`), whose label on the Settings page now says so. No migration.
- `ReminderMailer.writeOwnerReminder`: "“Dune” is due back from Grandma tomorrow" / "“Dune” is overdue at Grandma", explaining that Grandma isn't on Lendloop, so the reminder comes to the owner.
- Name suggestions in the Lend form: `LoanRepository.findOutsiderNamesOf` (this owner's earlier names, most recently used first, at most 50) feeds one `<datalist>` on My things.
  - When lending, a name equal to an earlier one ignoring case keeps the earlier spelling, so "grandma" is the same Grandma.
- Tests:
  - `LoanReminderTests`: the old "owner hears once" test is replaced by 3 tests: the full schedule, the switch off, and a retry.
  - `OutsideLoanTests` +1: suggestions per owner, and spelling reuse.
  - `PostgresMigrationTest`: the name query (group by / order by max / limit) on PostgreSQL.
  - 124 in total.

Verified locally:
- `./mvnw test`: 121 passed, 3 skipped (PostgreSQL, no Docker; CI runs them).

Not verified locally: the datalist in a browser. It's the browser's own dropdown; the HTML is covered by `OutsideLoanTests`.

## 2026-11-05: Milestone 3d, book details from an ISBN (Open Library)

Why: next step 2 in this log, and books are the most common thing lent between friends. Typing the title and author by hand is the slowest part of adding one. [Open Library's API](https://openlibrary.org/developers/api) is free and needs no key. It only asks frequent users to send a `User-Agent` with the app name and a contact.

Done:
- `book` package (`item` depends on it, not the other way round):
  - `Isbn` record: parses what people type (ISBN-10 with `X`, ISBN-13, hyphens, spaces), checks the check digit and always holds ISBN-13.
  - `@ValidIsbn`: a Bean Validation constraint, blank or valid.
  - `BookLookup` interface and `BookInfo` record. `BookInfo` turns a book into the item name ("Title: Subtitle", ≤ 100) and description ("By A, B · Publisher, date", ≤ 1000).
  - `OpenLibraryClient` uses Spring's `RestClient` on the JDK `HttpClient`, with a 3 s connect / 5 s read timeout and redirects followed:
    - `GET /isbn/{isbn}.json` gives the **edition**, not the work. That's the copy on the shelf, e.g. a translation with its own title and publisher. It redirects to `/books/OL…M.json`.
    - Then `GET /authors/OL…A.json` for at most 3 authors. A failing author is skipped. An author key from the response is only put into a URL if it matches `/authors/OL\d+A`.
    - 404 means not found; other errors and timeouts raise `BookLookupUnavailableException`.
  - Config: `lendloop.openlibrary.base-url` / `user-agent` (`OPENLIBRARY_*` variables). The tests point it at `openlibrary.invalid`, so a mistake can never call out.
- Flyway `V7__item_isbn.sql`: `item.isbn varchar(13)` with a "13 characters" check. The form keeps what was typed; the service stores ISBN-13.
- Add item page: a separate **GET** form, "Adding a book? Look it up by ISBN" (`/items/new?isbn=…`). It only reads; nothing is saved until the item form is submitted.
  - Found: name, description, category Book and ISBN are filled in.
  - Unknown: category and ISBN are still filled in.
  - Invalid or over 30 characters: a message, and no call.
  - Open Library down: a friendly message, and the details go to the log.
- An ISBN field on the item form (add and edit), and "ISBN 978…" on My things.
- **Fixed the phone layout of the Lend form (from 3c).** At 375 px the open form made the page 556 px wide and cut off *Lend it*. The cause: the mobile rule `.item-actions form { display: flex }` also hit the form nested in `<details>`. It now only targets `.item-actions > form`, and the action row wraps.
- Tests: `IsbnTest` (20), `BookInfoTest` (4), `OpenLibraryClientTest` (6, `MockRestServiceServer`), `IsbnLookupTests` (8, `@MockitoBean BookLookup`). 121 in total.

Verified locally:
- `./mvnw test`: 118 passed, 3 skipped (PostgreSQL, no Docker; CI runs them, including V7 on PostgreSQL).
- Against the real Open Library, with a temporary test (deleted afterwards):
  - `0-14-032872-6` → "Fantastic Mr. Fox", Roald Dahl, Puffin, October 1, 1988 (through the `/isbn` → `/books` redirect).
  - `9780441013593` → "Dune", Frank Herbert, Ace Trade.
- In the browser, using a new `lendloop-memory-demo` launch config (port 8093, in-memory H2), because an older instance from 3 Nov on port 8080 still held the H2 file:
  - The lookup filled in the form; *Add item* saved it, and My things shows the description and "ISBN 9780140328721".
  - `12345` gives the invalid-ISBN message.
  - No horizontal overflow at 375 px.
  - Also did the open browser check from 3b/3c: Settings at 375 px is fine with both boxes ticked. The Lend form had the overflow bug above; it is fixed and re-checked (page width 375 = viewport), and the desktop layout is unchanged.

Trade-offs:
- The lookup is synchronous during a page load, up to 1 + 3 calls. Fine for a form someone is filling in; the timeouts keep it bounded.
- No cache: one user rarely looks up the same ISBN twice.
- Edition data over the search API's work-level data: more accurate for the owner's copy, but authors cost extra calls.

## 2026-11-04: Milestone 3c, lending to someone outside the app

Why: next step 1 in this log. Every single-user lending tracker lets you type a borrower's name or pick a contact, because many borrowers will never install the app (e.g. [Borrow : Loan Reminder](https://apps.apple.com/app/id1168418139), [Who Has My Stuff?](https://f-droid.org/en/packages/de.freewarepoint.whohasmystuff/)). Without it, LendLoop could only record loans inside a group.

Done:
- Flyway `V6__outside_borrowers.sql`:
  - `loan.borrower_id` is nullable, new `loan.borrower_name varchar(60)`.
  - `ck_loan_one_borrower`: exactly one of the two is set.
  - `ck_loan_outside_never_requested`: a named borrower's loan is only ever `ACTIVE` or `RETURNED`.
- `Loan.toOutsider(item, name, dueDate, now)` creates the loan directly as `ACTIVE`. New `isOutsideApp()`, `isBorrowedBy(userId)` and `getBorrowerDisplayName()`, so templates and emails never dereference a null borrower.
- `LoanService.lendToOutsider`: only the owner's item (otherwise 404), the name is trimmed with line breaks / tabs / control characters collapsed to one space (it goes into email subjects), 1–60 characters, the same due-date limits as approving (shared `requireValidDueDate`), and not while the item is already out.
  - Works for items that aren't shared with groups: lending in person doesn't need a group.
  - `dueDateRange()` gives both pages the same min / suggested (+14 days) / max for the date input; `LoanController.DEFAULT_LOAN_DAYS` moved into `LoanService`.
- `POST /loans/outside` (PRG back to My things) and a **Lend** disclosure form on each item at home on My things.
- **All loan queries now `left join fetch l.borrower`.** An inner fetch join silently drops loans without a borrower; this was the main risk of the change. `findRecentClosed` filters on the join alias `b.id` instead of `l.borrower.id`.
- Templates: Loans shows "with Grandma" + a "Not on Lendloop" tag; history and the group page use `isBorrowedBy(...)` instead of `loan.borrower.id`, which would throw on a null borrower. Group members only see "On loan".
- Reminder job: no borrower email for outside loans; the owner's one-off overdue notice works as before ("“Dune” is overdue at Grandma").
- Tests: `OutsideLoanTests` (6, new), `LoanTest` +1 (20), `LoanReminderTests` +1 (9), `PostgresMigrationTest` extended (outside loan through the left-joined queries on PostgreSQL). 83 in total.

Verified locally:
- `./mvnw test`: 80 passed, 3 skipped (PostgreSQL, no Docker; CI runs them, including V6 on PostgreSQL).
- Mutation check: putting back the inner `join fetch l.borrower` in `findForOwner` makes `OutsideLoanTests.theOwnerLendsToSomeoneOutsideTheApp` fail. Reverted afterwards.

Not verified locally: the Lend form in a browser (starting the local server was declined again in this automated run). To check by hand: `./mvnw spring-boot:run`, open My things, click **Lend** on an item, type a name, and check My things, Loans and the phone layout (375 px) of the open form.

Trade-offs:
- A plain name, no contacts table: simplest thing that records the loan. The same person typed twice is two names (see next steps).
- Outside borrowers have no email, so they get no reminders; the owner only gets the one overdue notice, not weekly ones.

## 2026-11-03: Milestone 3b, email settings and the owner's overdue notice

Done:
- Flyway `V5__notification_settings.sql`: `app_user.remind_borrowed` and `app_user.notify_lent_overdue`, both `default true not null`, so existing users keep getting reminders.
- **Settings page** (`/settings`, linked from your name in the top bar): `SettingsForm`, `SettingsService`, `SettingsController`, `settings.html`.
  - Change the display name (same rules as sign-up) and the two email switches.
  - The principal in the session is replaced after saving, so the top bar shows the new name at once. Since Spring Security 6 a changed `SecurityContext` isn't saved automatically, so the controller calls `HttpSessionSecurityContextRepository.saveContext`.
- **Owner notice:** `Loan.ownerNoticeDueOn(today)` is true together with the borrower's *first* overdue reminder only. The email: "“Catan” is overdue at Sam".
- `LoanReminderJob` checks both switches. A loan is recorded as handled even if nobody wanted an email, so the owner notice stays a one-off.
  - A failed borrower email: not recorded, retried next run (as before), and the owner isn't emailed yet either.
  - A failed owner email: only logged. Retrying it would send the borrower's reminder twice.
- Every email ends with a link to the Settings page (common advice: give a preferences link in every notification email, and only offer settings the system enforces).
- `ReminderMailer`: shared `mail(...)` and `deliver(...)`; the owner notice reuses the header-injection protection.
- Tests: `SettingsTests` (6), `LoanReminderTests` +3 (8), `LoanTest` +1 (19). 75 in total.

Verified locally:
- `./mvnw test`: 72 passed, 3 skipped (PostgreSQL, no Docker; CI runs them, including V5 on PostgreSQL through `ddl-auto=validate`).

Not verified locally: the Settings page in a browser (starting the local server was declined in this automated run). To check by hand: `./mvnw spring-boot:run`, log in, click your name, rename yourself and untick a box, and check the top bar and that the boxes stay unticked after a reload.

## 2026-11-03: Milestone 3a, due-date reminders by email

Done:
- `spring-boot-starter-mail`; Flyway `V4__loan_reminders.sql` adds `loan.last_reminder_on`.
- `Loan.reminderDueOn(today)` decides which email is due, as a pure method like the other loan rules:
  - the day before the due date: `DUE_TOMORROW`;
  - the first overdue day, then every 7 days: `OVERDUE`;
  - nothing if a reminder already went out today, or the loan isn't active.
- `LoanReminderJob`: `@Scheduled` (cron from `lendloop.reminders.cron`, default 08:00, `-` disables it; `@EnableScheduling` on the app).
  - Loads active loans due by tomorrow (one fetch-join query), sends, then records the day with an `update` that only touches `last_reminder_on`, so it can't overwrite a "returned" made meanwhile.
  - A `MailException` is logged and not recorded, so it's retried next run; the other loans still get theirs.
- `ReminderMailer`: plain-text emails with a link to `/loans` (`lendloop.base-url`).
  - Uses `ObjectProvider<JavaMailSender>`: Spring Boot only creates a sender when `spring.mail.host` is set; without one the email is logged. So the zero-setup local run still works.
  - Control characters in item and user names are replaced by spaces, so a name can't add mail headers.
- Mailpit (`axllent/mailpit:v1.33`) in `compose.yaml`; mail variables in `.env.example` and the README.
- Tests: `LoanTest` +2 (7 cases), `LoanReminderTests` (5, mocked `JavaMailSender`), `PostgresMigrationTest` checks the new query and update on PostgreSQL.

Trade-offs:
- A reminder is recorded even when mail isn't configured and it only went to the log, so configuring mail later doesn't send a backlog.
- "Due tomorrow" can arrive on the day the loan was approved if the due date is tomorrow.
- One app instance assumed (no distributed lock such as ShedLock).

Verified locally:
- `./mvnw test`: 62 passed, 3 skipped (PostgreSQL, no Docker; CI runs them).
- Packaged jar on port 8092 with the cron set to every 15 s and a demo loan due tomorrow in the local H2 database: Flyway applied V4, the first run logged the "“Catan” is due back tomorrow" email to Friend with the link, and the next run sent 0. The demo loan row was deleted afterwards.

Not verified locally: sending through a real SMTP server / Mailpit (no Docker here). The mail path is covered by the mocked `JavaMailSender` tests only.

## 2026-11-03: All HTTP tests run without a test transaction

Follow-up to the `LazyInitializationException` found in milestone 2b.

Done:
- New `WebTest` base class (`@SpringBootTest` + MockMvc, **not** `@Transactional`):
  - empties `loan`, `item`, `group_member`, `app_group`, `app_user` after each test, children first;
  - asserts that these are all the app's tables, so a future migration can't add a table whose rows leak between tests.
- `RegistrationTests`, `ItemControllerTests`, `GroupTests` and `LoanFlowTests` extend it. `LoanFlowTests` lost its own copy of the cleanup.
- `LoanConstraintTests` stays transactional: it only uses repositories, no HTTP, so it has no "after the transaction" step to protect.

Result: all 52 local tests still pass, so items, groups and registration had no hidden lazy-loading bug.

Proof that the tests now catch this class of bug: removing the `join fetch` from `GroupMemberRepository.findByGroupIdAndUserId` makes `GroupTests` fail with `LazyInitializationException` (before, under `@Transactional`, it would have passed). The change was reverted after the check.

## 2026-11-03: Milestone 2b, loans

Done:
- Flyway `V3__loans.sql`: a `loan` table with a status check constraint and "active loans have a due date".
  - **One active loan per item** and **one open request per person and item**. On PostgreSQL these would be partial unique indexes, but H2 has none. Instead, nullable copy columns (`active_item_id`, `open_request_item_id` + `open_request_borrower_id`) are filled only while the rule applies, and normal unique constraints ignore their NULLs. This works on both databases.
- `Loan` entity = state machine:
  - `approve`, `decline`, `cancel`, `markReturned` check the current status, throw `LoanRuleException` with a user-facing message otherwise, and keep the copy columns in sync.
  - `isOverdueOn(today)`.
- `LoanService` checks who may act (otherwise 404):
  - **Borrower:** request (needs a shared group, a lendable item that isn't theirs, the item not lent out, no open request already) and cancel.
  - **Owner:** approve with a due date (today to +365 days), decline and mark returned.
  - The repository flushes inside the method, so a race hits the unique constraint as a `DataIntegrityViolationException`, which the controller reports as a normal message.
- `LoanController` (PRG with flash messages), `loans/list.html` with sections:
  - waiting for your answer (date input, default +14 days),
  - lent out, borrowed by you, your requests, history (last 20).
- Group page: *Ask to borrow* (with an optional message) / *Requested* / *On loan* / *You have it* / *Lent to X*.
- My things: *Lent to X, Due ...*. A lent-out item can't be deleted (that would cascade-delete the loan).
- Top bar: *Loans* link with a badge counting requests that wait for you (one count query per GET).
- `Dates` bean for English dates in templates (`${@dates.format(d)}`) and "today" from the shared `Clock`.
- Tests:
  - `LoanTest`: 13, pure unit tests, parameterized over every forbidden transition.
  - `LoanFlowTests`: 10.
  - `LoanConstraintTests`: 3.
  - `PostgresMigrationTest` +1: loan queries with `nulls last` / `limit`, and the constraint on PostgreSQL.
  - 55 in total.

**Bug found in the browser, not by the tests:** asking to borrow failed with `LazyInitializationException`. The controller read the item owner's name after the service's transaction had ended, and open-in-view is off.
- The MockMvc tests passed because `@Transactional` test classes run every request inside the test's transaction, so the Hibernate session never closes.
- Fix: `ItemRepository.findWithOwnerById` (fetch join).
- `LoanFlowTests` now runs **without** a test transaction and empties the tables after each test. With the old code it fails with the same exception (checked by temporarily reverting the fix).
- The other MockMvc test classes are still transactional and could hide the same kind of bug (next step 2).

Verified locally:
- `./mvnw test`: 52 passed, 3 skipped (PostgreSQL, no Docker; CI runs them).
- In the browser with two accounts:
  - Friend withdrew the request left over from the crash, then asked again from the group: "Asked Demo for 'Catan'", and the group page shows *Requested*.
  - Demo saw badge 1 and the message, with a date input defaulting to 17 Nov 2026 (min today, max +1 year). *Lend it* -> "Lent 'Catan' to Friend until 17 Nov 2026".
  - My things showed *Lent to Friend, Due 17 Nov 2026*. Deleting was refused with the message.
  - *It's back* -> "'Catan' is back", and the history shows Returned and Cancelled.
  - No horizontal overflow at 375 px, and no server exceptions after the fix.

## 2026-11-03: Milestone 2a, groups with invite links

Done:
- Flyway `V2__groups.sql`:
  - `app_group`: name, unique invite token, expiry.
  - `group_member`: role OWNER / MEMBER, `unique (group_id, user_id)`.
- `group` package:
  - `AppGroup`, `GroupMember`, repositories (fetch joins, because open-in-view is off).
  - `InviteTokens`: `SecureRandom`, 32 bytes, Base64url.
  - `GroupService` with a `Clock` bean.
  - `GroupController`, `JoinController`.
- **Rules:**
  - Only members see a group; anyone else gets 404.
  - Only the owner sees and replaces the invite link; a member gets 403.
  - The owner can't leave.
  - Links expire after 7 days, and a new link disables the old one.
- **Join flow:**
  - `GET /join/{token}` only asks to confirm and `POST` joins, so link previews in chat apps can't join anyone.
  - A logged-out user is taken through login and back. For that, `defaultSuccessUrl` no longer always goes to `/items`.
  - Joining twice keeps one membership: a check, plus the unique constraint for a race. That race is handled in the controller, outside the transaction: a failed insert marks the transaction rollback-only, so catching the exception inside the `@Transactional` method would still fail at commit.
- The group page lists the members' lendable items with the owner's name, the members, the invite link with a Copy button, *Make a new link* and *Leave group*.
- Top bar navigation (*My things* / *Groups*) via a `@ControllerAdvice`, since Thymeleaf 3.1 no longer exposes `#request`.
- `Referrer-Policy: same-origin` so tokens in URLs aren't sent to other sites.
- Tests: `GroupTests` (11), `PostgresMigrationTest` extended with the group queries. 28 tests in total.

Found in the browser: the invite expiry showed as "10 lis 2026" on a Czech browser (Thymeleaf formats with the request locale) in an otherwise English UI. It is now formatted in English in the controller.

Verified locally:
- `./mvnw test`: 26 passed, 2 skipped (PostgreSQL, no Docker).
- In the browser (`spring-boot:run`):
  - Creating a group shows the invite link and the owner's lendable item.
  - Logging out and opening the link leads to login; the second account was registered and logged in, and came back to "Join Flat 4B?".
  - Joining shows the group with *Catan · from Demo*, members Demo (Owner) and Friend, and *Leave group*.
  - No horizontal overflow at 375 px.
  - The fixed date reads "10 Nov 2026, 15:09".

Not verified locally: the PostgreSQL tests (CI runs them). Screenshots timed out in this session, so the phone layout was checked by measuring overflow, not by looking.

## 2026-11-03: Milestone 1, accounts and own items

Why this project: a search for apps people ask for found a gap. Lending trackers exist only as single-user iPhone apps, and the one open-source app (Lendix) is archived. An HN commenter asked for a library of owned / wanted / lendable things. The owner approved the proposal and a public repo `Gallileee/lendloop`.

Done:
- Spring Boot 4.1.2 project (from start.spring.io) on Java 17, Maven Wrapper, package by feature.
- Flyway `V1__users_and_items.sql` (`app_user`, `item`), written to run on both PostgreSQL and H2 in PostgreSQL mode; Hibernate `ddl-auto=validate`.
- Sign-up (`/register`):
  - Bean Validation: email format, name ≤ 60, password ≥ 10 characters and ≤ 72 UTF-8 bytes (BCrypt's limit), passwords must match.
  - The email is trimmed and lower-cased. Duplicates are caught by both a check and the unique constraint (race).
  - Passwords are stored with the delegating encoder (`{bcrypt}`).
- Login with email (`/login`), logout. CSRF on, everything except login / sign-up / CSS requires a login.
- Items (`/items`): list, add, edit, delete, "can be lent" switch, 7 categories. The service scopes every query to the owner, and someone else's id gives 404.
- Thymeleaf pages, light and dark CSS, phone layout, an error page for 404 / 500.
- Tests: 15 MockMvc / Spring Boot tests on H2, plus `PostgresMigrationTest` (Testcontainers, skipped without Docker).
- CI: GitHub Actions `./mvnw -B verify` on Java 17 (has Docker, so the PostgreSQL test runs there).
- `compose.yaml` (PostgreSQL 17) and `.env.example`, README, MIT license.

Found by the tests: an email with a trailing space (e.g. pasted) failed `@Email` before the service could trim it. The form setter now trims.

Verified locally (JDK 17.0.1, Maven 3.10 via the wrapper):
- `./mvnw test`: 15 passed, 1 skipped (PostgreSQL test, no Docker on this machine).
- In the browser (`spring-boot:run`, port 8091):
  - Sign-up shows the server-side error for a short password.
  - A valid sign-up redirects to login with a confirmation.
  - Login works with a differently cased email.
  - An added item appears with its category and status.
  - `/items/999/edit` shows the 404 page.
  - Every form field has a label; no horizontal overflow at 375 px.

Not verified locally: the PostgreSQL test (needs Docker). CI runs it.

## Next steps (in order)

1. Password reset by email (a signed, single-use, short-lived token; reuse the mail setup and the throttle idea for reset requests).
2. Group management: rename, delete, owner removes a member, hand over ownership.
3. Czech translation (`messages_cs.properties`). Then search could also ignore accents (e.g. a normalised search column).
4. Book covers on the group page, behind an opt-in setting (they reveal the viewer's IP to Open Library).
5. Deployment on a free tier (set `UNSUBSCRIBE_SECRET`).

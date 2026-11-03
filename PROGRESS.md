# Progress log

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

1. Overdue reminders: `@Scheduled` daily job + email (Spring Mail, Mailpit in compose).
2. Loan to someone outside the app (just a name).
3. ISBN lookup via Open Library for books.
4. Login rate limiting, password reset by email.
5. Czech translation (`messages_cs.properties`).
6. Group management: rename, delete, owner removes a member, hand over ownership.
7. Deployment on a free tier.

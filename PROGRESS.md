# Progress log

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

1. **Groups:** create a group, invite link with a random token (expiry), join, list members. Only members see the group's items.
2. **Loans:** `loan` table and a state machine (requested → active → returned; declined; cancelled) in one service with tests for every allowed and forbidden transition; only the owner approves, only the borrower cancels; an item can have at most one active loan (DB constraint).
3. Overdue reminders: `@Scheduled` daily job + email (Spring Mail, Mailpit in compose).
4. Loan to someone outside the app (just a name).
5. ISBN lookup via Open Library for books.
6. Login rate limiting, password reset by email.
7. Czech translation (`messages_cs.properties`).
8. Deployment on a free tier.

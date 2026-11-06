# LendLoop

A shared lending library for a group of friends, flatmates or a dorm: everyone lists the things they're happy to lend (books, tools, board games, a tent), friends ask to borrow them, and both sides can see who has what and when it's due back.

## Why

People keep asking online for an app that tracks what they own, what they want and what they've lent out ([Ask HN, Aug 2026](https://news.ycombinator.com/item?id=49259602)). The apps that exist today are single-user iPhone apps ([Borrowed](https://apps.apple.com/us/app/id1538284368), [The Give-Back Log](https://apps.apple.com/app/id6767269640), [BorrowHub](https://apps.apple.com/pg/app/borrowhub/id6755054243)). The only open-source one, [Lendix](https://github.com/ow9t/lendix), was archived in December 2025. None of them is shared between people, so only the lender remembers the loan. LendLoop makes it a two-sided record inside a group.

## Status

**Milestones 1 and 2 are done**, and milestone 3 has started: accounts, your own items, groups with invite links, loans from request to return, loans to people who aren't on the app, email reminders about due dates, a Settings page to choose which emails you get, book details from an ISBN, and search on the group page (see [Roadmap](#roadmap)).

## Features

- **Sign up and log in** with email and password.
  - Emails are trimmed and compared case-insensitively.
  - Passwords need at least 10 characters with no composition rules (NIST SP 800-63B). They're stored as BCrypt hashes, and anything BCrypt would silently truncate (over 72 bytes) is rejected.
  - **Failed logins are limited** (`LoginThrottle`): after 5 failures for one email address, or 30 from one IP address, within 15 minutes, logins are paused. The pause ends when the oldest of those failures is 15 minutes old.
    - While paused, even the right password is refused without being checked, so a lucky guess isn't revealed.
    - Unknown emails count the same as real ones, so the answer doesn't tell which accounts exist.
    - A successful login clears that email's count, but not the IP's.
  - **Forgot your password?** (link on the login page) emails a link to choose a new one:
    - The link holds 256 random bits and works **once, for 30 minutes**. Asking again turns off the older link.
    - Only a SHA-256 hash of it is stored, so someone who can read the database still can't use a link.
    - The answer is the same whether or not the account exists, even if sending fails. At most 3 requests per email and 10 per IP an hour.
    - The new password follows the sign-up rules. Resetting also ends a login pause, so you can log in straight away.
  - **Change password** on Settings (asks for the current one; wrong guesses count like failed logins) and **Log out everywhere else**.
  - A password change, a reset, or *Log out everywhere else* **ends every other session**:
    - Each account has a `session_version`, raised by those actions, and each session remembers the number it logged in with.
    - `SessionVersionFilter` compares the two on every logged-in request (one column by primary key) and logs out a stale session with "You were logged out because…". This works without a session registry and with several app instances.
    - The browser that made the change stays logged in, and gets a new session id after a password change.
- **My things:** add, edit and delete items with a name, category, optional description and a "friends can ask to borrow it" switch.
- **Add a book by its ISBN.** Type or scan the 10 or 13 digits on Add item and *Look it up*: the title, authors, publisher and date are filled in from [Open Library](https://openlibrary.org/developers/api) (free, no API key), and you check them before saving. ISBN-10 and ISBN-13 are both accepted with or without hyphens, the check digit is verified, and the ISBN is stored as ISBN-13. If Open Library is slow (3 s connect / 5 s read timeout), down, or doesn't know the book, you get a message and fill it in by hand.
- **Ownership is enforced on the server.** Every item query is scoped to the logged-in user, and someone else's item answers 404, so its existence isn't revealed.
- **Groups** of friends:
  - Create a group and share its **invite link**. It holds a random 256-bit token and expires after 7 days.
  - The group page lists the members and everything they mark as lendable, with who owns it.
  - **Search the group's things:** by text in the name or description (so a book's author works too), by category, and "free right now" (leaves out what's lent out). Typing or scanning an ISBN in any format finds the book. The search is a plain GET form, so a search can be bookmarked or shared, and the count shows "2 of 9". `%` and `_` in the search box are taken literally.
  - Only members can see a group (others get 404). Only its creator manages the invite link: they can make a new one, which disables the old one at once.
  - Members can leave a group. The creator can't, so a group always has someone who manages the link.
- **Invite links are safe to paste into a chat.**
  - Opening the link only asks "Join …?". Joining needs a POST, so a chat app's link preview can't add anyone.
  - Someone logged out is sent to the login page (or sign-up) and then back to the invite.
  - Following the link again doesn't create a second membership: there's a check, plus a unique constraint for two clicks at the same moment.
  - Pages send `Referrer-Policy: same-origin`, so tokens in URLs never leak to other sites.
- **Loans**, from asking to giving back:
  - On a group page, a member can **ask to borrow** someone else's item, with an optional message.
  - The owner sees the request under **Loans** (with a badge in the top bar) and either **lends it** with a return date (default 14 days, at most a year) or **declines**.
  - The borrower can withdraw a request until it's answered.
  - The owner confirms when it's back (**It's back**).
  - Loans past their date say "Overdue since ..." in words, not just in colour.
  - My things shows "Lent to Sam, Due 17 Nov 2026". A lent-out item can't be deleted.
  - **Lending to someone who isn't on LendLoop** (a neighbour, a grandparent): on My things, **Lend** takes their name and a return date, and the loan starts right away. It shows under Loans with a "Not on Lendloop" tag and is returned like any other. Group members only see "On loan", not the name. Such a borrower gets no emails, but the owner still gets the overdue notice.
  - Who may do what is enforced on the server. Only the owner answers and confirms the return, and only the borrower withdraws; anyone else gets 404. You can only ask people you share a group with.
- **Due-date reminders by email.** A daily job (8:00 by default) emails the borrower:
  - the day before the due date ("“Catan” is due back tomorrow");
  - on the first day a loan is overdue, then once a week until it's returned.
  - The owner gets one email on the first overdue day too ("“Catan” is overdue at Sam"), not the weekly ones.
  - Lent to someone outside the app? They have no email here, so the owner gets their reminders instead, to pass on in person: "“Dune” is due back from Grandma tomorrow", then overdue on the first day and weekly until it's marked as returned.
  - Each email is recorded on the loan as it's sent, so a second run or a restart the same day sends nothing twice. A failed email is retried on the next run and doesn't stop the others.
  - Without a mail server configured, the emails are written to the log instead, so the app still runs with zero setup. `docker compose up -d` starts [Mailpit](https://mailpit.axllent.org/), which catches them at http://localhost:8025.
- **Settings** (click your name in the top bar): change your name, and turn each kind of email on or off (reminders about things you borrowed; notices about things you lent out, including the reminders for people outside the app). Both are on by default, and every email links to this page.
- **One-click unsubscribe from any email** (RFC 8058), no login needed:
  - Every email has a `List-Unsubscribe` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click` header, so Gmail, Outlook and other mail apps show their own *Unsubscribe* button. The same link is in the footer: "Stop them: …".
  - The link turns off only the kind of email it came with (borrowed vs lent out), for only that person. It carries the user id and the list, signed with HMAC-SHA256 (`UNSUBSCRIBE_SECRET`), so a link can't be changed to someone else's account.
  - Opening the link only asks "Stop these emails?" and the button POSTs, because mail systems open links in emails to scan them. The mail provider's own POST needs no cookies or CSRF token (the signature proves the link is ours). Repeating it is harmless.
  - A forged, damaged or outdated link gets the same "This link doesn't work" page (404).
  - A new name shows in the top bar at once: the logged-in principal in the session is replaced on save.
- **The loan rules are a small state machine:** `REQUESTED -> ACTIVE -> RETURNED`, or `REQUESTED -> DECLINED / CANCELLED`. A loan to someone outside the app starts at `ACTIVE`.
  - The `Loan` entity refuses every other change.
  - The database checks that a loan names exactly one borrower (an app user *or* a typed name), and that only app users have open requests.
  - The database also guarantees one active loan per item and one open request per person and item, even when two requests race.
- Server-rendered pages that work without JavaScript (JavaScript only adds a confirmation before deleting), in light and dark mode, and usable on a phone.

## Tech stack

| Part | Choice | Why |
|---|---|---|
| Language | Java 17 | Runs on the JDK most university machines have; Spring Boot 4 supports it |
| Framework | Spring Boot 4.1 (Web MVC, Security, Data JPA, Validation) | The most common Java backend stack in job listings |
| Views | Thymeleaf, plain CSS | Server-rendered HTML: no build step, forms and CSRF protection work out of the box |
| Database | H2 (local default), PostgreSQL (production, CI) | Zero setup locally; the same Flyway migrations run on both |
| Migrations | Flyway | The schema is versioned in `src/main/resources/db/migration`, and Hibernate only validates it |
| Tests | JUnit 5, Spring MockMvc, Spring Security Test, Testcontainers | HTTP-level tests of the real app, plus one test on real PostgreSQL |

## Run locally

Requirements: JDK 17 or newer. Maven isn't needed; the wrapper downloads it.

```bash
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

Open http://localhost:8080, create an account and add some things. Data is kept in `./data/` (an H2 file database, ignored by git). Delete that folder to start over.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `SPRING_DATASOURCE_URL` | H2 file in `./data` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/lendloop` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `sa` / empty | Database credentials |
| `SPRING_MAIL_HOST` / `SPRING_MAIL_PORT` | not set | SMTP server for reminder emails (Mailpit: `localhost` / `1025`). Not set: emails go to the log |
| `BASE_URL` | `http://localhost:${PORT}` | Public address of the app, for links in emails |
| `MAIL_FROM` | `Lendloop <no-reply@lendloop.local>` | Sender of the emails |
| `UNSUBSCRIBE_SECRET` | not set | Signs the unsubscribe links in emails, at least 32 characters (`openssl rand -base64 32`). Not set: a random secret per run, so links in emails stop working after a restart. **Set it in any deployment**; changing it invalidates all links already sent |
| `OPENLIBRARY_USER_AGENT` | `Lendloop/0.1 (+https://github.com/Gallileee/lendloop)` | Sent with ISBN lookups. Open Library asks regular API users to name their app and a contact, so add your email when you deploy |
| `OPENLIBRARY_BASE_URL` | `https://openlibrary.org` | Where ISBN lookups go |
| `LOGIN_MAX_FAILURES_PER_EMAIL` / `LOGIN_MAX_FAILURES_PER_IP` / `LOGIN_WINDOW` | `5` / `30` / `15m` | Failed-login limits. Behind a reverse proxy, also set `server.forward-headers-strategy=framework`, or every user shares the proxy's IP |
| `REMINDERS_CRON` | `0 0 8 * * *` | When the reminder job runs (Spring cron, server time zone); `-` turns it off |

To use PostgreSQL and a local mail catcher (needs Docker):

```bash
docker compose up -d
```

Then set the variables from [.env.example](.env.example) and run the app as above. Never commit a real `.env`.

## Tests

```bash
./mvnw test
```

The HTTP tests extend `WebTest`. It deliberately has **no test transaction**: each request commits on its own like in production, and the tables are emptied after every test. A transactional test would keep the Hibernate session open for the whole request and hide lazy-loading bugs. `WebTest` also fails if a migration adds a table it doesn't clean.

- `RegistrationTests`: sign-up, validation, duplicate emails, CSRF and login.
- `IsbnTest`: ISBN-10 and ISBN-13 check digits (including `X`), hyphens and spaces, ISBN-10 to ISBN-13, and what's rejected (parameterized, no Spring).
- `BookInfoTest`: how a found book becomes an item name and description, and cutting it to the form's limits.
- `OpenLibraryClientTest`: the HTTP side against `MockRestServiceServer`: the URLs and `User-Agent`, reading the edition and author JSON, 404 = not found, 500 = unavailable, a failing author skipped, and author keys from the response only used in a URL when they look like `/authors/OL…A` (at most 3).
- `IsbnLookupTests`: the lookup box on Add item over HTTP with a mocked `BookLookup` (found, unknown, invalid, unreachable), saving the ISBN as ISBN-13, rejecting a wrong one, and the edit page.
- `ItemControllerTests`: creating, editing and deleting items, and that another user can't see or change them.
- `LoanTest`: the loan state machine on its own, including every forbidden transition (parameterized), with no Spring or database.
- `LoanFlowTests`: the whole loan flow over HTTP, who may do what, due-date limits, one person at a time, overdue display, and no deleting lent-out items. (See PROGRESS.md for the bug that led to `WebTest`.)
- `LoanReminderTests`: the reminder job against the database with a mocked `JavaMailSender`: the "due tomorrow" email and its text, no second email on the same day, overdue on the first day and then weekly, the owner's one-off overdue notice, the owner getting the due-tomorrow and weekly overdue reminders for loans to someone outside the app (and a failed one retried), users who turned emails off, nothing for returned loans or requests, a failed email retried without blocking the others, and no header injection through item names.
- `OutsideLoanTests`: lending to someone outside the app over HTTP: the form on My things, name clean-up, how it shows on My things, Loans and the group page (members don't see the name), returning and lending again, input limits, one active loan per item, owner-only (404), the database's "exactly one borrower" checks, and earlier names suggested (per owner) and reused with their spelling.
- `UnsubscribeLinksTest`: the signed links on their own: accepted for the right user and list only, another secret or a cut-off signature rejected, malformed parameters (parameterized), too short a secret refused at startup, and a random secret per run.
- `UnsubscribeTests`: the link over HTTP, logged out: GET only asks, the button turns off just that list, the mail provider's one-click POST works without a CSRF token and can be repeated, forged links and deleted accounts get 404, and other POSTs still need a CSRF token.
- `PasswordResetTests`: the whole reset over HTTP with a mocked `Mailer`: the emailed link sets a new password (the old one stops working), a link works once, an unknown email gets the same answer and no email, a newer link replaces the older one, expired links, only the hash stored, password rules with the link still usable afterwards, malformed tokens, 3 requests an hour, a failed email looking the same (and rolled back), a reset ending a login pause, and CSRF.
- `AccountSecurityTests`: with real login sessions as "devices": a password change logs out the others but keeps this one (with a new session id), wrong current passwords change nothing and count like failed logins, the password rules, log out everywhere else, a reset logging out every device, a deleted account's session ending, a name change keeping the session, and login + CSRF on both forms.
- `LoginThrottleTest`: the sliding window with a test clock (no Spring): when the pause starts and ends, case-insensitive emails, one IP and many accounts, success clears the email but not the IP, the longer of two waits, purging old entries, and very long emails.
- `LoginRateLimitTests`: the limits over HTTP: the 5th wrong password pauses the email (even the right password, from any IP), unknown emails are paused the same way, a success forgets typos, one IP guessing at 30 accounts is paused, the login page explains the pause, and a missing CSRF token is still rejected first and not counted.
- `SettingsTests`: the Settings page needs a login, shows both emails on for a new account, saves the name and unticked boxes, refreshes the name in the session, and refuses a blank name.
- `LoanConstraintTests`: the database refuses two active loans for one item and duplicate open requests.
- `ItemFilterTest`: turning query parameters into a filter (trimming, cutting, unknown categories ignored) and escaping `%`, `_` and `\` for LIKE (no Spring).
- `GroupSearchTests`: the group search against the database: name and description ignoring case, wildcards taken literally, ISBN in any format, category, "free right now" with a lent-out item, the "2 of 4" count, the form keeping its values, "nothing matches" vs an empty group, bad parameters ignored, and outsiders still getting 404.
- `GroupTests`: creating groups, joining by link (including after logging in), expired, unknown and replaced links, owner-only invite management, outsiders getting 404, what the group page lists, and leaving.
- `LendloopApplicationTests`: the app starts, migrations apply and the entities match the schema.
- `PostgresMigrationTest`: the same on real PostgreSQL through Testcontainers, plus the group (including the filtered search), loan, reminder and password-reset queries and the loan constraints. It is **skipped without Docker** and always runs in CI ([GitHub Actions](.github/workflows/ci.yml)).

## Project structure

```
src/main/java/io/github/gallileee/lendloop/
  user/       account entity, sign-up and settings forms, validation and services, signed unsubscribe links
  security/   Spring Security config, the logged-in user, failed-login limits, password reset
  mail/       Mailer: plain-text emails, logged when no mail server is set
  item/       items: entity, repository, service (ownership checks), controller
  book/       ISBN parsing and validation, Open Library lookup (BookLookup, OpenLibraryClient)
  group/      groups, membership, invite tokens, join flow
  loan/       loans: state machine (Loan), rules (LoanService), pages, reminder job and emails
  web/        home redirect, login page, top bar data, date formatting
src/main/resources/
  db/migration/   Flyway SQL migrations
  templates/      Thymeleaf pages
  static/css/     stylesheet
```

The code is organised by feature (`user`, `item`) rather than by layer, so everything about one feature sits together.

## Limitations

- Only due dates are emailed. New requests and answers are only visible in the app.
- If the owner's overdue email fails, it isn't retried (retrying would remind the borrower twice).
- One-click unsubscribe also needs the mail to be DKIM-signed by the sending domain before Gmail and Yahoo show their button. That's set up on the mail server, not in the app.
- Unsubscribe links don't expire. The only thing one can do is turn an email off, which the user can undo in Settings.
- The reminder job assumes one running instance of the app; two instances would both send.
- "Today" and due dates use the server's time zone.
- Only the owner can mark a loan as returned. If they forget, it stays lent out.
- A borrower outside the app is just a name, with no contact details, so their reminders go to the owner. Earlier names are suggested and a name typed in different case is merged, but a typo ("Granma") is still a new person.
- Invite tokens are stored in plain text so the owner can see the link again. Anyone with read access to the database could use a link until it expires.
- The invite expiry is shown in the server's time zone. Behind a reverse proxy, set `server.forward-headers-strategy=framework` so invite links use the public host and https.
- Groups can't be renamed or deleted, and members can't be removed by the owner yet.
- Sign-up says when an email is already registered, which reveals that the account exists. That's acceptable for an app between friends, but it should change before a public launch.
- There's no email verification yet: anyone can sign up with someone else's address (they just can't reset its password without the inbox).
- Without a mail server, the reset email (with its link) only goes to the log. That's meant for local runs; configure mail before others use the app.
- "Is there an account?" is hidden from the reset page's text, but not from its timing: sending an email takes longer than not sending one.
- Failed-login counts are kept in memory: a restart forgets them, and two app instances would each count separately.
- Someone who knows an email address can pause that person's logins for 15 minutes by failing on purpose. That's the usual trade-off of per-account limits. The pause is short, and failures while paused don't extend it.
- The UI is in English only.
- Group search is a substring match (`LIKE '%…%'`), not full-text search: no typo tolerance, no ranking, no accents ignored ("Bila" doesn't find "Bílá"), and it can't use an index. Fine for a group's few hundred things.
- ISBN lookup needs Open Library to be online, and its data is only as good as the catalogue (some editions have no author or an odd publisher name). Book covers aren't shown.

## Roadmap

1. ~~Accounts and your own items~~ (done)
2. ~~**Groups and loans:** invite links, membership; ask to borrow, lend with a due date or decline, withdraw, mark returned~~ (done)
3. **Reminders and more:** ~~a daily job emails borrowers about due and overdue loans (Mailpit locally)~~ (done); ~~email settings and an overdue notice for the owner~~ (done); ~~record a loan to someone outside the app~~ (done); ~~fill in book details from an ISBN via Open Library~~ (done); ~~search and filter on the group page~~ (done); ~~one-click unsubscribe from emails (RFC 8058)~~ (done); ~~failed-login limits~~ (done); ~~password reset by email~~ (done); ~~change password, log out other sessions~~ (done).
4. Later: photos, installable PWA, iCal feed of due dates, Czech translation, deployment.

The detailed progress log is in [PROGRESS.md](PROGRESS.md).

## License

[MIT](LICENSE)

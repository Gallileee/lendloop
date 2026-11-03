# LendLoop

A shared lending library for a group of friends, flatmates or a dorm: everyone lists the things they're happy to lend (books, tools, board games, a tent), friends ask to borrow them, and both sides can see who has what and when it's due back.

## Why

People keep asking online for an app that tracks what they own, what they want and what they've lent out ([Ask HN, Aug 2026](https://news.ycombinator.com/item?id=49259602)). The apps that exist today are single-user iPhone apps ([Borrowed](https://apps.apple.com/us/app/id1538284368), [The Give-Back Log](https://apps.apple.com/app/id6767269640), [BorrowHub](https://apps.apple.com/pg/app/borrowhub/id6755054243)). The only open-source one, [Lendix](https://github.com/ow9t/lendix), was archived in December 2025. None of them is shared between people, so only the lender remembers the loan. LendLoop makes it a two-sided record inside a group.

## Status

**Milestones 1 and 2a are done:** accounts, your own list of items, and groups with invite links. Borrow requests and reminders come next (see [Roadmap](#roadmap)).

## Features

- **Sign up and log in** with email and password.
  - Emails are trimmed and compared case-insensitively.
  - Passwords need at least 10 characters with no composition rules (NIST SP 800-63B). They're stored as BCrypt hashes, and anything BCrypt would silently truncate (over 72 bytes) is rejected.
- **My things:** add, edit and delete items with a name, category, optional description and a "friends can ask to borrow it" switch.
- **Ownership is enforced on the server.** Every item query is scoped to the logged-in user, and someone else's item answers 404, so its existence isn't revealed.
- **Groups** of friends:
  - Create a group and share its **invite link**. It holds a random 256-bit token and expires after 7 days.
  - The group page lists the members and everything they mark as lendable, with who owns it.
  - Only members can see a group (others get 404). Only its creator manages the invite link: they can make a new one, which disables the old one at once.
  - Members can leave a group. The creator can't, so a group always has someone who manages the link.
- **Invite links are safe to paste into a chat.**
  - Opening the link only asks "Join …?". Joining needs a POST, so a chat app's link preview can't add anyone.
  - Someone logged out is sent to the login page (or sign-up) and then back to the invite.
  - Following the link again doesn't create a second membership: there's a check, plus a unique constraint for two clicks at the same moment.
  - Pages send `Referrer-Policy: same-origin`, so tokens in URLs never leak to other sites.
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

To use PostgreSQL locally (needs Docker):

```bash
docker compose up -d
```

Then set the variables from [.env.example](.env.example) and run the app as above. Never commit a real `.env`.

## Tests

```bash
./mvnw test
```

- `RegistrationTests`: sign-up, validation, duplicate emails, CSRF and login.
- `ItemControllerTests`: creating, editing and deleting items, and that another user can't see or change them.
- `GroupTests`: creating groups, joining by link (including after logging in), expired, unknown and replaced links, owner-only invite management, outsiders getting 404, what the group page lists, and leaving.
- `LendloopApplicationTests`: the app starts, migrations apply and the entities match the schema.
- `PostgresMigrationTest`: the same on real PostgreSQL through Testcontainers, plus the group queries. It is **skipped without Docker** and always runs in CI ([GitHub Actions](.github/workflows/ci.yml)).

## Project structure

```
src/main/java/io/github/gallileee/lendloop/
  user/       account entity, sign-up form, validation and service
  security/   Spring Security config and the logged-in user (AppUserDetails)
  item/       items: entity, repository, service (ownership checks), controller
  group/      groups, membership, invite tokens, join flow
  web/        home redirect, login page, top bar navigation
src/main/resources/
  db/migration/   Flyway SQL migrations
  templates/      Thymeleaf pages
  static/css/     stylesheet
```

The code is organised by feature (`user`, `item`) rather than by layer, so everything about one feature sits together.

## Limitations

- Items in a group can be seen but not borrowed yet: borrow requests are milestone 2b.
- Invite tokens are stored in plain text so the owner can see the link again. Anyone with read access to the database could use a link until it expires.
- The invite expiry is shown in the server's time zone. Behind a reverse proxy, set `server.forward-headers-strategy=framework` so invite links use the public host and https.
- Groups can't be renamed or deleted, and members can't be removed by the owner yet.
- Sign-up says when an email is already registered, which reveals that the account exists. That's acceptable for an app between friends, but it should change before a public launch.
- There's no password reset, email verification or rate limiting on login yet.
- The UI is in English only.

## Roadmap

1. ~~Accounts and your own items~~ (done)
2. ~~**Groups:** invite link, membership, the group's lendable items~~ (done)
   **Loans:** ask to borrow; the owner approves or declines and sets a due date; mark it returned. Loan states: requested → active → returned, or declined / cancelled.
3. **Reminders:** a daily job emails borrowers about overdue loans (Mailpit locally); record a loan to someone outside the app; fill in book details from an ISBN via Open Library.
4. Later: photos, installable PWA, iCal feed of due dates, Czech translation, deployment.

The detailed progress log is in [PROGRESS.md](PROGRESS.md).

## License

[MIT](LICENSE)

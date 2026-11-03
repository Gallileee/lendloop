-- The day the borrower was last emailed about a loan's due date, so the daily job sends each
-- reminder once, even if it runs twice in a day or the app restarts.
alter table loan add column last_reminder_on date;

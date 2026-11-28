-- Emails about borrow requests (RequestEmails): the owner hears about a new request, the borrower
-- about the answer. On by default like the other emails, also for existing users; one click in
-- any of them, or the Settings page, turns them off.
alter table app_user add column notify_requests boolean default true not null;

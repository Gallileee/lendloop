-- Unconfirmed accounts that nobody uses are deleted after a while (UnconfirmedAccountCleanup).
-- This is when the "confirm or it will be deleted" email went out; null until then, and set back
-- to null when the account starts being used. An account is only deleted some days after it.
alter table app_user add column deletion_warned_at timestamp with time zone;

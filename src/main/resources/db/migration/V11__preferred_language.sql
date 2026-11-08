-- The language Lendloop's emails are written in ("en" or "cs"): set from the page language at
-- sign-up, changed on the Settings page. Existing accounts keep getting English emails.
alter table app_user add column preferred_language varchar(8) default 'en' not null;

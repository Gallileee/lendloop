-- Which emails a user wants (Settings page). Both on by default, also for existing users,
-- who were already getting the due-date reminders.
alter table app_user add column remind_borrowed boolean default true not null;
alter table app_user add column notify_lent_overdue boolean default true not null;

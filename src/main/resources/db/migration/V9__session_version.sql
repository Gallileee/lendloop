-- Raised whenever the password changes or the user logs out everywhere. Each session remembers
-- the number it logged in with; a session with an older number is ended (SessionVersionFilter).
alter table app_user add column session_version integer default 0 not null;

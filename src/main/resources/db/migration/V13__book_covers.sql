-- Whether to show book covers (loaded from Open Library) on the item lists. Off by default:
-- each cover is fetched by the viewer's browser, so Open Library sees the viewer's IP address.
alter table app_user add column show_book_covers boolean default false not null;

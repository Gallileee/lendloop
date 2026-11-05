-- Books can carry their ISBN, always stored as the 13 digits of an ISBN-13 (no hyphens).
alter table item add column isbn varchar(13);
alter table item add constraint ck_item_isbn_13_digits check (isbn is null or length(isbn) = 13);

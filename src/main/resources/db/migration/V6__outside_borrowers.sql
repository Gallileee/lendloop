-- Loans to someone who isn't on Lendloop (a neighbour, a grandparent): the owner types their
-- name instead of picking a user. Such a loan starts ACTIVE right away, because there is
-- nobody in the app to send a request.

alter table loan alter column borrower_id drop not null;
alter table loan add column borrower_name varchar(60);

-- Exactly one of the two says who has the item.
alter table loan add constraint ck_loan_one_borrower
    check ((borrower_id is null and borrower_name is not null)
        or (borrower_id is not null and borrower_name is null));

-- Only app users can have an open request (the request flow needs someone to answer).
alter table loan add constraint ck_loan_outside_never_requested
    check (borrower_id is not null or status in ('ACTIVE', 'RETURNED'));

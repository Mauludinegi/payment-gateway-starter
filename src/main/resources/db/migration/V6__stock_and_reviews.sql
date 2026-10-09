-- Null stock means unlimited (digital products). Limited stock is taken when an order is placed and
-- returned when it expires unpaid.
alter table products add column stock integer check (stock >= 0);
alter table products add column version bigint not null default 0;

-- stock_held: this order currently holds stock. stock_short: it was paid after expiring, and the stock it had
-- released was gone by then, so the admin has to restock or refund.
alter table orders add column stock_held boolean not null default false;
alter table orders add column stock_short boolean not null default false;

-- Only five one-hour review slots, to show limited stock in the demo catalogue.
update products set stock = 5 where id = 'review-1h';

create table product_reviews (
    id         uuid primary key,
    product_id varchar(40)              not null references products (id),
    user_id    uuid                     not null references users (id),
    rating     smallint                 not null check (rating between 1 and 5),
    comment    varchar(1000),
    hidden     boolean                  not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint uq_product_reviews_product_user unique (product_id, user_id)
);

create index idx_product_reviews_product on product_reviews (product_id, created_at);

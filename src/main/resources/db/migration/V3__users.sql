create table users (
    id            uuid primary key,
    external_id   varchar(140) not null unique,
    email         varchar(254),
    name          varchar(100) not null,
    picture_url   varchar(1000),
    created_at    timestamp with time zone not null,
    last_login_at timestamp with time zone not null
);

-- Orders created before sign-in existed have no user.
alter table orders add column user_id uuid references users (id);
create index idx_orders_user on orders (user_id, created_at);

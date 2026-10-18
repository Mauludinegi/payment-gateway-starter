create table user_fcm_tokens (
    id          uuid primary key,
    user_id     uuid not null references users (id) on delete cascade,
    token       varchar(500) not null unique,
    device_info varchar(255),
    updated_at  timestamp with time zone not null
);

create index idx_fcm_tokens_user on user_fcm_tokens (user_id);

create table chat_rooms (
    id              uuid primary key,
    customer_id     uuid not null references users (id) on delete cascade,
    status          varchar(20) not null default 'OPEN',
    created_at      timestamp with time zone not null,
    last_message_at timestamp with time zone not null,
    constraint ck_chat_rooms_status check (status in ('OPEN', 'CLOSED'))
);

create index idx_chat_rooms_customer on chat_rooms (customer_id);
create index idx_chat_rooms_last_message on chat_rooms (last_message_at desc);

create table chat_messages (
    id          uuid primary key,
    room_id     uuid not null references chat_rooms (id) on delete cascade,
    sender_id   uuid not null references users (id) on delete cascade,
    sender_role varchar(20) not null,
    sender_name varchar(100) not null,
    text        text not null,
    created_at  timestamp with time zone not null,
    constraint ck_chat_messages_sender_role check (sender_role in ('CUSTOMER', 'ADMIN'))
);

create index idx_chat_messages_room on chat_messages (room_id, created_at asc);

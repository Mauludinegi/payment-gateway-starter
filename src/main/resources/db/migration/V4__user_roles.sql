alter table users add column role varchar(20) not null default 'CUSTOMER';
alter table users add constraint ck_users_role check (role in ('CUSTOMER', 'ADMIN'));

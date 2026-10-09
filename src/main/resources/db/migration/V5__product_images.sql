-- Object key in the image store (Supabase Storage or the local media folder); the URL is built from it.
alter table products add column image_key varchar(200);
alter table products add column updated_at timestamp with time zone;

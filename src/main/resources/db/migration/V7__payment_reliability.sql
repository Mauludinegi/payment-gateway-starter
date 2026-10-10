-- Starting a payment is idempotent: a retried request with the same key returns the same attempt.
alter table payment_attempts add column idempotency_key varchar(64);
create unique index uq_payment_attempts_idempotency on payment_attempts (order_id, idempotency_key);

-- Kept so a create whose outcome was unknown can be retried with the same request.
alter table payment_attempts add column mobile_number varchar(20);
alter table payment_attempts add column gateway_error varchar(500);

-- Webhooks that match no payment yet are kept and replayed instead of being dropped.
alter table webhook_events add column provider_ref varchar(100);
alter table webhook_events add column attempt_hint uuid;
alter table webhook_events add column replay_pending boolean not null default false;
create index idx_webhook_events_replay on webhook_events (replay_pending, received_at);

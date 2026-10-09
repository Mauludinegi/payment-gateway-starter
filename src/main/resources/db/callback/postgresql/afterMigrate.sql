-- Supabase serves the public schema through its Data API. With row level security on and no policies, the anon
-- and authenticated roles see nothing; the app connects as the tables' owner, which RLS does not restrict.
-- Runs after every migration, so new tables are covered too.
do $$
declare
    t record;
begin
    for t in select tablename from pg_tables where schemaname = '${flyway:defaultSchema}' and not rowsecurity loop
        execute format('alter table %I.%I enable row level security', '${flyway:defaultSchema}', t.tablename);
    end loop;
end
$$;

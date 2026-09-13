CREATE TABLE IF NOT EXISTS public.jiradash_snapshots (
    id character varying(255) NOT NULL,
    jql text NOT NULL,
    payload jsonb NOT NULL,
    fetched_at timestamp,
    fetched_by_user_id character varying(255),
    fetched_by_name character varying(255),
    CONSTRAINT jiradash_snapshots_pkey PRIMARY KEY (id)
);

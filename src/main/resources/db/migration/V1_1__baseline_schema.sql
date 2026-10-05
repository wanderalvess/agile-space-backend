-- Baseline do schema (todas as tabelas/indices/constraints refletidas nas entidades
-- @Entity atuais). Gerado a partir de um boot com hibernate.ddl-auto=create contra
-- banco vazio + pg_dump --schema-only, pois nenhuma migration anterior cria o schema
-- base (V1-V7 sao só incrementais). Todas as instrucoes sao idempotentes
-- (IF NOT EXISTS / guarda de duplicate_object) para nao quebrar bancos que ja tem
-- esse schema criado por hibernate.ddl-auto=update no passado.

-- Name: action_plan_participants; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.action_plan_participants (
    board_id uuid NOT NULL,
    participant_id character varying(255)
);

-- Name: action_plan_tasks; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.action_plan_tasks (
    task_order integer,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    board_id uuid NOT NULL,
    id uuid NOT NULL,
    status character varying(50),
    author_id character varying(255),
    how text,
    how_much character varying(255),
    what text,
    when_field text,
    where_location text,
    who character varying(255),
    why text
);

-- Name: action_plans; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.action_plans (
    is_public boolean,
    created_at timestamp(6) without time zone,
    id uuid NOT NULL,
    sprint_id character varying(100),
    creator_id character varying(255) NOT NULL,
    team character varying(255),
    title character varying(255) NOT NULL
);

-- Name: api_key_scopes; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.api_key_scopes (
    api_key_id uuid NOT NULL,
    scope character varying(255)
);

-- Name: api_keys; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.api_keys (
    created_at timestamp(6) without time zone,
    last_used_at timestamp(6) without time zone,
    revoked_at timestamp(6) without time zone,
    id uuid NOT NULL,
    key_hash character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    owner_role character varying(255),
    owner_user_id character varying(255),
    squad_id character varying(255)
);

-- Name: app_release_changes; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.app_release_changes (
    change_text text,
    release_id character varying(255) NOT NULL
);

-- Name: app_releases; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.app_releases (
    is_published boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone,
    created_by character varying(255),
    description text,
    display_date character varying(255) NOT NULL,
    icon_class character varying(255),
    icon_name character varying(255),
    id character varying(255) NOT NULL,
    tag character varying(255) NOT NULL,
    title character varying(255) NOT NULL,
    type character varying(255) NOT NULL
);

-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.audit_logs (
    created_at timestamp(6) without time zone NOT NULL,
    action character varying(255) NOT NULL,
    details text,
    id character varying(255) NOT NULL,
    performed_by character varying(255) NOT NULL
);

-- Name: brainstorming_boards; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.brainstorming_boards (
    phase character varying(50),
    created_at character varying(255),
    creator_id character varying(255),
    id character varying(255) NOT NULL,
    team character varying(255),
    title character varying(255),
    participant_ids jsonb,
    settings jsonb,
    timer jsonb
);

-- Name: brainstorming_groups; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.brainstorming_groups (
    group_order integer,
    board_id character varying(255) NOT NULL,
    color character varying(255),
    created_at character varying(255),
    id character varying(255) NOT NULL,
    title character varying(255)
);

-- Name: brainstorming_ideas; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.brainstorming_ideas (
    author_id character varying(255),
    board_id character varying(255) NOT NULL,
    color character varying(255),
    content text,
    created_at character varying(255),
    group_id character varying(255),
    id character varying(255) NOT NULL,
    parent_id character varying(255),
    "position" jsonb,
    qualifiers jsonb,
    votes jsonb
);

-- Name: brainstorming_participants; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.brainstorming_participants (
    is_creator boolean,
    role character varying(50),
    board_id character varying(255) NOT NULL,
    db_id character varying(255) NOT NULL,
    last_active character varying(255),
    nickname character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: daily_checkins; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.daily_checkins (
    has_blocker boolean,
    is_private boolean,
    created_at timestamp(6) without time zone,
    checkin_date character varying(10) NOT NULL,
    blocker_duration character varying(255),
    blockers text,
    id character varying(255) NOT NULL,
    squad_id character varying(255) NOT NULL,
    today text,
    user_avatar character varying(255),
    user_id character varying(255) NOT NULL,
    user_name character varying(255) NOT NULL,
    user_role character varying(255),
    yesterday text
);

-- Name: daily_reports; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.daily_reports (
    report_date character varying(10) NOT NULL,
    blockers text,
    id character varying(255) NOT NULL,
    "timestamp" character varying(255) NOT NULL,
    today text,
    user_id character varying(255) NOT NULL,
    yesterday text
);

-- Name: feedbacks; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.feedbacks (
    score integer,
    created_at timestamp(6) without time zone NOT NULL,
    comment text,
    id character varying(255) NOT NULL,
    status character varying(255),
    tool_name character varying(255) NOT NULL,
    user_id character varying(255)
);

-- Name: global_announcements; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.global_announcements (
    created_at timestamp(6) without time zone NOT NULL,
    content text NOT NULL,
    created_by character varying(255) NOT NULL,
    id character varying(255) NOT NULL,
    title character varying(255) NOT NULL
);

-- Name: health_check_boards; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.health_check_boards (
    scale_type character varying(50),
    status character varying(50),
    created_at character varying(255),
    creator_id character varying(255),
    id character varying(255) NOT NULL,
    sprint_name character varying(255),
    team character varying(255),
    dimensions jsonb,
    participant_ids jsonb,
    summary jsonb
);

-- Name: health_check_participants; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.health_check_participants (
    role character varying(50),
    board_id character varying(255) NOT NULL,
    db_id character varying(255) NOT NULL,
    global_role character varying(255),
    nickname character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: health_check_votes; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.health_check_votes (
    participant_role character varying(50),
    value character varying(50) NOT NULL,
    dimension_key character varying(100) NOT NULL,
    board_id character varying(255) NOT NULL,
    comment text,
    id character varying(255) NOT NULL,
    participant_id character varying(255) NOT NULL,
    vote_timestamp character varying(255) NOT NULL
);

-- Name: jolt_project_versions; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.jolt_project_versions (
    version_number integer NOT NULL,
    created_at timestamp(6) without time zone,
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    author_name character varying(255),
    commit_message text,
    created_by character varying(255),
    flow_edges text,
    flow_nodes text,
    input_json text,
    spec_json text,
    target_json text
);

-- Name: jolt_projects; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.jolt_projects (
    is_public boolean NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    id uuid NOT NULL,
    mapping_mode character varying(20),
    category character varying(50),
    entity_name character varying(100),
    author_email character varying(255),
    author_id character varying(255) NOT NULL,
    author_name character varying(255),
    description text,
    flow_edges text,
    flow_nodes text,
    input_json text,
    name character varying(255) NOT NULL,
    spec_json text,
    squad_id character varying(255),
    target_json text
);

-- Name: knowledge_conversations; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.knowledge_conversations (
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    id uuid NOT NULL,
    messages text,
    title character varying(255),
    user_id character varying(255) NOT NULL
);

-- Name: knowledge_kb; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.knowledge_kb (
    views integer,
    byte_size bigint,
    created_at timestamp(6) without time zone,
    deleted_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    id uuid NOT NULL,
    status character varying(50),
    author_id character varying(255) NOT NULL,
    category character varying(255),
    content text,
    deleted_by character varying(255),
    embedding text,
    folder_id character varying(255),
    folder_name character varying(255),
    full_path character varying(255),
    module_id character varying(255),
    module_name character varying(255),
    tdn_id character varying(255),
    title character varying(255) NOT NULL,
    updated_by character varying(255)
);

-- Name: knowledge_tags; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.knowledge_tags (
    document_id uuid NOT NULL,
    tag character varying(255)
);

-- Name: knowledge_token_usage; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.knowledge_token_usage (
    total_tokens bigint NOT NULL,
    updated_at timestamp(6) without time zone,
    user_id character varying(255) NOT NULL,
    user_name character varying(255)
);

-- Name: knowledge_user_ai_settings; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.knowledge_user_ai_settings (
    updated_at timestamp(6) without time zone,
    byok_api_key character varying(1000),
    model character varying(255),
    user_id character varying(255) NOT NULL
);

-- Name: password_reset_requests; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.password_reset_requests (
    approved_at timestamp(6) without time zone,
    requested_at timestamp(6) without time zone NOT NULL,
    approved_by character varying(255),
    id character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    temp_password character varying(255),
    user_email character varying(255) NOT NULL,
    user_name character varying(255)
);

-- Name: poker_participants; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.poker_participants (
    is_facilitator boolean,
    role character varying(50),
    db_id character varying(255) NOT NULL,
    email character varying(255),
    global_role character varying(255),
    last_seen character varying(255),
    nickname character varying(255) NOT NULL,
    room_id character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: poker_rooms; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.poker_rooms (
    participants_count integer,
    votes_revealed boolean,
    deck_type character varying(50),
    mode character varying(50),
    active_issue_id character varying(255),
    created_at character varying(255),
    creator_id character varying(255),
    current_topic character varying(255),
    id character varying(255) NOT NULL,
    selective_revoting_role character varying(255),
    session_ended_at character varying(255),
    session_started_at character varying(255),
    team character varying(255),
    title character varying(255),
    issues_queue jsonb,
    participant_ids jsonb,
    reference_baseline jsonb,
    revealed_issues jsonb,
    settings jsonb,
    summary jsonb,
    timer jsonb
);

-- Name: poker_rounds; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.poker_rounds (
    skipped boolean,
    deck_type character varying(50),
    dev_points character varying(50),
    qa_points character varying(50),
    id character varying(255) NOT NULL,
    issue_id character varying(255),
    note text,
    room_id character varying(255) NOT NULL,
    round_timestamp character varying(255),
    topic character varying(255),
    role_points jsonb,
    stats jsonb,
    votes jsonb
);

-- Name: poker_votes; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.poker_votes (
    confidence character varying(50),
    participant_role character varying(50),
    value character varying(50) NOT NULL,
    id character varying(255) NOT NULL,
    issue_id character varying(255),
    participant_global_role character varying(255),
    participant_id character varying(255) NOT NULL,
    participant_nickname character varying(255),
    room_id character varying(255) NOT NULL,
    vote_timestamp character varying(255) NOT NULL
);

-- Name: project_member_roles; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.project_member_roles (
    is_leadership boolean NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    project_id character varying(50) NOT NULL,
    role_key character varying(50) NOT NULL,
    avatar_url character varying(255),
    display_name character varying(255) NOT NULL,
    email character varying(255),
    id character varying(255) NOT NULL,
    jira_account_id character varying(255),
    role_name character varying(255) NOT NULL,
    user_id character varying(255)
);

-- Name: projects; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.projects (
    auto_tdn_doc boolean,
    dev_team_size integer,
    disable_auto_subtasks boolean,
    engineering_only_expedition boolean,
    optional_worklog boolean,
    saas_expedition boolean,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    id character varying(50) NOT NULL,
    creation_date character varying(255),
    locality character varying(255),
    name character varying(255) NOT NULL,
    profields_raw_json text,
    segment_name character varying(255),
    specific_subtasks text,
    status character varying(255),
    tribe_name character varying(255),
    vice_president character varying(255),
    vp_area character varying(255)
);

-- Name: prompt_collection_items; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.prompt_collection_items (
    order_index integer NOT NULL,
    collection_id uuid NOT NULL,
    prompt_id uuid NOT NULL
);

-- Name: prompt_collections; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.prompt_collections (
    created_at timestamp(6) without time zone,
    id uuid NOT NULL,
    visibility character varying(50),
    description text,
    name character varying(255) NOT NULL,
    owner_id character varying(255) NOT NULL,
    owner_name character varying(255)
);

-- Name: prompt_comments; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.prompt_comments (
    created_at timestamp(6) without time zone,
    id uuid NOT NULL,
    prompt_id uuid NOT NULL,
    author_avatar character varying(255),
    author_id character varying(255) NOT NULL,
    author_name character varying(255),
    author_role character varying(255),
    author_squad character varying(255),
    content text NOT NULL
);

-- Name: prompt_hub; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.prompt_hub (
    fork_count integer,
    use_count integer,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    id uuid NOT NULL,
    impact character varying(50),
    status character varying(50),
    type character varying(50),
    visibility character varying(50),
    architecture_link character varying(255),
    author_avatar character varying(255),
    author_id character varying(255) NOT NULL,
    author_name character varying(255),
    author_role character varying(255),
    author_squad character varying(255),
    business_goal character varying(255),
    content text,
    description text,
    gem_link character varying(255),
    target_audience character varying(255),
    title character varying(255) NOT NULL
);

-- Name: prompt_tags; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.prompt_tags (
    prompt_id uuid NOT NULL,
    tag character varying(255)
);

-- Name: retro_board_columns; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.retro_board_columns (
    column_order integer,
    board_id character varying(255) NOT NULL,
    id character varying(255),
    theme character varying(255),
    title character varying(255)
);

-- Name: retro_boards; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.retro_boards (
    auto_reveal_on_timer_end boolean,
    auto_sort_on_vote_end boolean,
    health_check_enabled boolean,
    is_authors_revealed boolean,
    is_cards_revealed boolean,
    sync_stage_enabled boolean,
    timer_initial_duration integer,
    timer_remaining_on_pause integer,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    template_key character varying(50),
    voting_status character varying(50),
    sprint_id character varying(100),
    squad_id character varying(100),
    active_column_key character varying(255),
    creator_id character varying(255) NOT NULL,
    health_check_question text,
    id character varying(255) NOT NULL,
    team character varying(255),
    timer_end_time character varying(255),
    timer_status character varying(255),
    title character varying(255) NOT NULL
);

-- Name: retro_card_votes; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.retro_card_votes (
    card_id character varying(255) NOT NULL,
    user_id character varying(255)
);

-- Name: retro_cards; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.retro_cards (
    is_done boolean,
    card_order bigint,
    column_key character varying(50) NOT NULL,
    assignee character varying(255),
    author_id character varying(255) NOT NULL,
    board_id character varying(255) NOT NULL,
    carried_from_board_id character varying(255),
    carried_from_board_title character varying(255),
    content text NOT NULL,
    due_date character varying(255),
    id character varying(255) NOT NULL,
    parent_id character varying(255),
    reactions jsonb
);

-- Name: retro_participants; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.retro_participants (
    is_creator boolean,
    board_id character varying(255) NOT NULL,
    db_id character varying(255) NOT NULL,
    global_role character varying(255),
    health_check_answer character varying(255),
    nickname character varying(255) NOT NULL,
    role character varying(255),
    user_id character varying(255) NOT NULL
);

-- Name: showcase_sessions; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.showcase_sessions (
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone,
    cover_image character varying(255),
    created_by character varying(255),
    default_sort character varying(255),
    description text,
    id character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    period character varying(255),
    sprint_name character varying(255),
    squad_name character varying(255),
    status character varying(255) NOT NULL,
    members jsonb,
    tasks jsonb
);

-- Name: sprint_plannings; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.sprint_plannings (
    created_at character varying(255),
    created_by character varying(255),
    id character varying(255) NOT NULL,
    title character varying(255),
    updated_at character varying(255),
    imported_poker_room_ids jsonb,
    members jsonb,
    settings jsonb,
    tasks jsonb
);

-- Name: squad_daily_snapshots; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.squad_daily_snapshots (
    bug_issues integer,
    done_issues integer,
    in_progress_issues integer,
    stale_issues integer,
    total_issues integer,
    logged_sec bigint,
    db_id character varying(255) NOT NULL,
    snapshot_date character varying(255) NOT NULL,
    squad_id character varying(255) NOT NULL,
    synced_at character varying(255)
);

-- Name: squad_issue_snapshots; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.squad_issue_snapshots (
    dates_are_inferred boolean,
    is_bug boolean,
    order_index integer,
    stale_since_days integer,
    estimate_sec bigint,
    logged_sec bigint,
    remaining_sec bigint,
    assignee_id character varying(255),
    assignee_name character varying(255),
    db_id character varying(255) NOT NULL,
    due_date character varying(255),
    issue_type character varying(255),
    jira_key character varying(255) NOT NULL,
    parent_key character varying(255),
    parent_title character varying(255),
    resolution_date character varying(255),
    sprint_id character varying(255),
    sprint_name character varying(255),
    squad_id character varying(255) NOT NULL,
    status character varying(255),
    status_category character varying(255),
    synced_at character varying(255),
    target_end character varying(255),
    target_start character varying(255),
    updated_at_jira character varying(255)
);

-- Name: squad_issue_worklog_cache; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.squad_issue_worklog_cache (
    db_id character varying(255) NOT NULL,
    jira_key character varying(255) NOT NULL,
    sprint_id character varying(255),
    squad_id character varying(255) NOT NULL,
    synced_at character varying(255),
    updated_at_jira character varying(255),
    worklog_author_names jsonb,
    worklog_by_author jsonb
);

-- Name: squad_member_metrics; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.squad_member_metrics (
    capacity_hours double precision,
    hours_logged double precision,
    issues_completed integer,
    issues_in_progress integer,
    utilization_pct double precision,
    assignee_id character varying(255) NOT NULL,
    assignee_name character varying(255),
    computed_at character varying(255),
    db_id character varying(255) NOT NULL,
    sprint_id character varying(255),
    squad_id character varying(255) NOT NULL
);

-- Name: squad_members; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.squad_members (
    capacity_hours_per_day double precision,
    system_calculated_capacity_hours_per_day double precision,
    calibration_notes character varying(255),
    claimed_by_uid character varying(255),
    db_id character varying(255) NOT NULL,
    display_name character varying(255),
    email character varying(255),
    jira_account_id character varying(255) NOT NULL,
    override_type character varying(255),
    role character varying(255),
    squad_id character varying(255) NOT NULL,
    updated_at character varying(255)
);

-- Name: squad_metrics_rollup; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.squad_metrics_rollup (
    bug_issues integer,
    done_issues integer,
    due_soon_issues integer,
    in_progress_issues integer,
    overdue_issues integer,
    stale_issues integer,
    total_issues integer,
    workdays_remaining integer,
    workdays_total integer,
    estimate_total_sec bigint,
    logged_total_sec bigint,
    remaining_total_sec bigint,
    computed_at character varying(255),
    sprint_id character varying(255),
    sprint_name character varying(255),
    squad_id character varying(255) NOT NULL,
    extra_metrics jsonb
);

-- Name: squad_panels; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.squad_panels (
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    id uuid NOT NULL,
    config text,
    name character varying(255) NOT NULL,
    owner_id character varying(255) NOT NULL,
    squad_id character varying(255) NOT NULL,
    type character varying(255),
    visibility character varying(255)
);

-- Name: squads; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.squads (
    default_daily_capacity_hours double precision,
    ranking_enabled boolean,
    reconcile_interval_hours integer,
    schema_version integer,
    active_sprint_id character varying(255),
    capacity_calculation_method character varying(255),
    capacity_formula character varying(255),
    capacity_jql text,
    id character varying(255) NOT NULL,
    jira_domain character varying(255),
    jira_project_key character varying(255),
    last_full_reconcile_at character varying(255),
    last_sync_at character varying(255),
    last_sync_status character varying(255),
    name character varying(255) NOT NULL,
    ranking_enabled_at character varying(255),
    sprint_field_id character varying(255),
    sync_jql text,
    updated_at character varying(255),
    phases jsonb,
    sprint_history jsonb
);

-- Name: support_ticket_replies; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.support_ticket_replies (
    is_admin boolean NOT NULL,
    created_at timestamp(6) without time zone,
    id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    author_id character varying(255),
    author_name character varying(255),
    message text NOT NULL
);

-- Name: support_tickets; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.support_tickets (
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    id uuid NOT NULL,
    status character varying(20) NOT NULL,
    message text NOT NULL,
    requester_email character varying(255),
    requester_id character varying(255) NOT NULL,
    requester_name character varying(255),
    subject character varying(255) NOT NULL
);

-- Name: system_configs; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.system_configs (
    key character varying(255) NOT NULL,
    value text NOT NULL
);

-- Name: user_focus_sessions; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_focus_sessions (
    duration_minutes integer NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    id character varying(255) NOT NULL,
    task_category character varying(255),
    user_id character varying(255) NOT NULL
);

-- Name: user_jira_configs; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_jira_configs (
    token character varying(1000) NOT NULL,
    domain character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: user_kanban_cards; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_kanban_cards (
    due_date timestamp(6) without time zone,
    exported_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone NOT NULL,
    card_priority character varying(255) NOT NULL,
    card_status character varying(255) NOT NULL,
    description text,
    id character varying(255) NOT NULL,
    origin_link character varying(255),
    tag character varying(255),
    title character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: user_quick_links; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_quick_links (
    created_at timestamp(6) without time zone NOT NULL,
    id character varying(255) NOT NULL,
    title character varying(255) NOT NULL,
    url character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: user_role_history; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_role_history (
    changed_at timestamp(6) without time zone,
    changed_by character varying(255),
    id character varying(255) NOT NULL,
    new_role character varying(255) NOT NULL,
    previous_role character varying(255),
    user_id character varying(255) NOT NULL
);

-- Name: user_snippets; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_snippets (
    created_at timestamp(6) without time zone NOT NULL,
    content text,
    id character varying(255) NOT NULL,
    language character varying(255),
    title character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: user_sticky_notes; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_sticky_notes (
    is_pinned boolean NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    color character varying(255) NOT NULL,
    content text NOT NULL,
    id character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: user_tdn_configs; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_tdn_configs (
    token character varying(1000) NOT NULL,
    base_url character varying(255) NOT NULL,
    label character varying(255),
    space character varying(255),
    user_id character varying(255) NOT NULL
);

-- Name: user_worklogs; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.user_worklogs (
    duration_minutes integer,
    is_jira boolean,
    log_date character varying(10) NOT NULL,
    jira_status character varying(50),
    id character varying(255) NOT NULL,
    task_id character varying(255),
    "timestamp" character varying(255) NOT NULL,
    title text NOT NULL,
    user_id character varying(255) NOT NULL
);

-- Name: users; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.users (
    daily_hours integer,
    is_active boolean,
    is_guest boolean,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    auth_provider character varying(255),
    avatar_seed character varying(255),
    default_project_id character varying(255),
    email character varying(255) NOT NULL,
    id character varying(255) NOT NULL,
    jira_account_id character varying(255),
    job_title character varying(255),
    name character varying(255) NOT NULL,
    password_hash character varying(255),
    role character varying(255),
    segment_name character varying(255),
    squad_id character varying(255),
    sso_id character varying(255),
    tribe_name character varying(255)
);

-- Name: vault_secrets; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.vault_secrets (
    is_burned boolean NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    expires_at timestamp(6) without time zone,
    expiration_type character varying(255) NOT NULL,
    id character varying(255) NOT NULL,
    iv character varying(255) NOT NULL,
    payload text NOT NULL
);

-- Name: work_items; Type: TABLE; Schema: public; Owner: -

CREATE TABLE IF NOT EXISTS public.work_items (
    points_estimated double precision,
    points_final double precision,
    created_at timestamp(6) without time zone,
    decided_at timestamp(6) without time zone,
    estimate_sec bigint,
    logged_sec bigint,
    remaining_sec bigint,
    target_end timestamp(6) without time zone,
    target_start timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    acceptance_criteria text,
    assignee_id character varying(255),
    assignee_name character varying(255),
    decided_by character varying(255),
    decision character varying(255),
    decision_feedback text,
    description text,
    dev_name character varying(255),
    evidence_problem text,
    evidence_solution text,
    evidence_video_url text,
    id character varying(255) NOT NULL,
    jira_key character varying(255) NOT NULL,
    parent_key character varying(255),
    parent_title character varying(255),
    priority character varying(255),
    qa_name character varying(255),
    sprint_id character varying(255),
    squad_id character varying(255) NOT NULL,
    status character varying(255) NOT NULL,
    title character varying(255) NOT NULL,
    type character varying(255)
);

-- Name: action_plan_tasks action_plan_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.action_plan_tasks
        ADD CONSTRAINT action_plan_tasks_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: action_plans action_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.action_plans
        ADD CONSTRAINT action_plans_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: api_keys api_keys_key_hash_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.api_keys
        ADD CONSTRAINT api_keys_key_hash_key UNIQUE (key_hash);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: api_keys api_keys_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.api_keys
        ADD CONSTRAINT api_keys_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: app_releases app_releases_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.app_releases
        ADD CONSTRAINT app_releases_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: app_releases app_releases_tag_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.app_releases
        ADD CONSTRAINT app_releases_tag_key UNIQUE (tag);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.audit_logs
        ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: brainstorming_boards brainstorming_boards_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.brainstorming_boards
        ADD CONSTRAINT brainstorming_boards_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: brainstorming_groups brainstorming_groups_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.brainstorming_groups
        ADD CONSTRAINT brainstorming_groups_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: brainstorming_ideas brainstorming_ideas_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.brainstorming_ideas
        ADD CONSTRAINT brainstorming_ideas_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: brainstorming_participants brainstorming_participants_board_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.brainstorming_participants
        ADD CONSTRAINT brainstorming_participants_board_id_user_id_key UNIQUE (board_id, user_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: brainstorming_participants brainstorming_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.brainstorming_participants
        ADD CONSTRAINT brainstorming_participants_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: daily_checkins daily_checkins_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.daily_checkins
        ADD CONSTRAINT daily_checkins_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: daily_reports daily_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.daily_reports
        ADD CONSTRAINT daily_reports_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: feedbacks feedbacks_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.feedbacks
        ADD CONSTRAINT feedbacks_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: global_announcements global_announcements_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.global_announcements
        ADD CONSTRAINT global_announcements_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: health_check_boards health_check_boards_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.health_check_boards
        ADD CONSTRAINT health_check_boards_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: health_check_participants health_check_participants_board_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.health_check_participants
        ADD CONSTRAINT health_check_participants_board_id_user_id_key UNIQUE (board_id, user_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: health_check_participants health_check_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.health_check_participants
        ADD CONSTRAINT health_check_participants_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: health_check_votes health_check_votes_board_id_participant_id_dimension_key_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.health_check_votes
        ADD CONSTRAINT health_check_votes_board_id_participant_id_dimension_key_key UNIQUE (board_id, participant_id, dimension_key);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: health_check_votes health_check_votes_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.health_check_votes
        ADD CONSTRAINT health_check_votes_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: jolt_project_versions jolt_project_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.jolt_project_versions
        ADD CONSTRAINT jolt_project_versions_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: jolt_projects jolt_projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.jolt_projects
        ADD CONSTRAINT jolt_projects_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: knowledge_conversations knowledge_conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.knowledge_conversations
        ADD CONSTRAINT knowledge_conversations_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: knowledge_kb knowledge_kb_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.knowledge_kb
        ADD CONSTRAINT knowledge_kb_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: knowledge_kb knowledge_kb_tdn_id_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.knowledge_kb
        ADD CONSTRAINT knowledge_kb_tdn_id_key UNIQUE (tdn_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: knowledge_token_usage knowledge_token_usage_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.knowledge_token_usage
        ADD CONSTRAINT knowledge_token_usage_pkey PRIMARY KEY (user_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: knowledge_user_ai_settings knowledge_user_ai_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.knowledge_user_ai_settings
        ADD CONSTRAINT knowledge_user_ai_settings_pkey PRIMARY KEY (user_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: password_reset_requests password_reset_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.password_reset_requests
        ADD CONSTRAINT password_reset_requests_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: poker_participants poker_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.poker_participants
        ADD CONSTRAINT poker_participants_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: poker_participants poker_participants_room_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.poker_participants
        ADD CONSTRAINT poker_participants_room_id_user_id_key UNIQUE (room_id, user_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: poker_rooms poker_rooms_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.poker_rooms
        ADD CONSTRAINT poker_rooms_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: poker_rounds poker_rounds_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.poker_rounds
        ADD CONSTRAINT poker_rounds_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: poker_votes poker_votes_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.poker_votes
        ADD CONSTRAINT poker_votes_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: poker_votes poker_votes_room_id_participant_id_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.poker_votes
        ADD CONSTRAINT poker_votes_room_id_participant_id_key UNIQUE (room_id, participant_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: project_member_roles project_member_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.project_member_roles
        ADD CONSTRAINT project_member_roles_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.projects
        ADD CONSTRAINT projects_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: prompt_collection_items prompt_collection_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.prompt_collection_items
        ADD CONSTRAINT prompt_collection_items_pkey PRIMARY KEY (order_index, collection_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: prompt_collections prompt_collections_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.prompt_collections
        ADD CONSTRAINT prompt_collections_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: prompt_comments prompt_comments_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.prompt_comments
        ADD CONSTRAINT prompt_comments_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: prompt_hub prompt_hub_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.prompt_hub
        ADD CONSTRAINT prompt_hub_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: retro_boards retro_boards_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.retro_boards
        ADD CONSTRAINT retro_boards_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: retro_cards retro_cards_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.retro_cards
        ADD CONSTRAINT retro_cards_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: retro_participants retro_participants_board_id_user_id_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.retro_participants
        ADD CONSTRAINT retro_participants_board_id_user_id_key UNIQUE (board_id, user_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: retro_participants retro_participants_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.retro_participants
        ADD CONSTRAINT retro_participants_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: showcase_sessions showcase_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.showcase_sessions
        ADD CONSTRAINT showcase_sessions_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: sprint_plannings sprint_plannings_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.sprint_plannings
        ADD CONSTRAINT sprint_plannings_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squad_daily_snapshots squad_daily_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squad_daily_snapshots
        ADD CONSTRAINT squad_daily_snapshots_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squad_issue_snapshots squad_issue_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squad_issue_snapshots
        ADD CONSTRAINT squad_issue_snapshots_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squad_issue_worklog_cache squad_issue_worklog_cache_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squad_issue_worklog_cache
        ADD CONSTRAINT squad_issue_worklog_cache_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squad_member_metrics squad_member_metrics_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squad_member_metrics
        ADD CONSTRAINT squad_member_metrics_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squad_members squad_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squad_members
        ADD CONSTRAINT squad_members_pkey PRIMARY KEY (db_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squad_members squad_members_squad_id_jira_account_id_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squad_members
        ADD CONSTRAINT squad_members_squad_id_jira_account_id_key UNIQUE (squad_id, jira_account_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squad_metrics_rollup squad_metrics_rollup_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squad_metrics_rollup
        ADD CONSTRAINT squad_metrics_rollup_pkey PRIMARY KEY (squad_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squad_panels squad_panels_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squad_panels
        ADD CONSTRAINT squad_panels_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: squads squads_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.squads
        ADD CONSTRAINT squads_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: support_ticket_replies support_ticket_replies_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.support_ticket_replies
        ADD CONSTRAINT support_ticket_replies_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: support_tickets support_tickets_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.support_tickets
        ADD CONSTRAINT support_tickets_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: system_configs system_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.system_configs
        ADD CONSTRAINT system_configs_pkey PRIMARY KEY (key);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: work_items uk_work_item_squad_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.work_items
        ADD CONSTRAINT uk_work_item_squad_key UNIQUE (squad_id, jira_key);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_focus_sessions user_focus_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_focus_sessions
        ADD CONSTRAINT user_focus_sessions_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_jira_configs user_jira_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_jira_configs
        ADD CONSTRAINT user_jira_configs_pkey PRIMARY KEY (user_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_kanban_cards user_kanban_cards_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_kanban_cards
        ADD CONSTRAINT user_kanban_cards_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_quick_links user_quick_links_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_quick_links
        ADD CONSTRAINT user_quick_links_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_role_history user_role_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_role_history
        ADD CONSTRAINT user_role_history_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_snippets user_snippets_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_snippets
        ADD CONSTRAINT user_snippets_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_sticky_notes user_sticky_notes_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_sticky_notes
        ADD CONSTRAINT user_sticky_notes_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_tdn_configs user_tdn_configs_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_tdn_configs
        ADD CONSTRAINT user_tdn_configs_pkey PRIMARY KEY (user_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: user_worklogs user_worklogs_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.user_worklogs
        ADD CONSTRAINT user_worklogs_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.users
        ADD CONSTRAINT users_email_key UNIQUE (email);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.users
        ADD CONSTRAINT users_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: users users_sso_id_key; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.users
        ADD CONSTRAINT users_sso_id_key UNIQUE (sso_id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: vault_secrets vault_secrets_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.vault_secrets
        ADD CONSTRAINT vault_secrets_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: work_items work_items_pkey; Type: CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.work_items
        ADD CONSTRAINT work_items_pkey PRIMARY KEY (id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: idx_pmr_email; Type: INDEX; Schema: public; Owner: -

CREATE INDEX IF NOT EXISTS idx_pmr_email ON public.project_member_roles USING btree (email);

-- Name: idx_pmr_jira_account; Type: INDEX; Schema: public; Owner: -

CREATE INDEX IF NOT EXISTS idx_pmr_jira_account ON public.project_member_roles USING btree (jira_account_id);

-- Name: idx_pmr_project; Type: INDEX; Schema: public; Owner: -

CREATE INDEX IF NOT EXISTS idx_pmr_project ON public.project_member_roles USING btree (project_id);

-- Name: idx_reset_email; Type: INDEX; Schema: public; Owner: -

CREATE INDEX IF NOT EXISTS idx_reset_email ON public.password_reset_requests USING btree (user_email);

-- Name: idx_reset_status; Type: INDEX; Schema: public; Owner: -

CREATE INDEX IF NOT EXISTS idx_reset_status ON public.password_reset_requests USING btree (status);

-- Name: idx_user_email; Type: INDEX; Schema: public; Owner: -

CREATE INDEX IF NOT EXISTS idx_user_email ON public.users USING btree (email);

-- Name: idx_user_jira_account; Type: INDEX; Schema: public; Owner: -

CREATE INDEX IF NOT EXISTS idx_user_jira_account ON public.users USING btree (jira_account_id);

-- Name: idx_user_sso; Type: INDEX; Schema: public; Owner: -

CREATE INDEX IF NOT EXISTS idx_user_sso ON public.users USING btree (sso_id);

-- Name: prompt_tags fk28q3bjeq4qobhxc3i4270j5gb; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.prompt_tags
        ADD CONSTRAINT fk28q3bjeq4qobhxc3i4270j5gb FOREIGN KEY (prompt_id) REFERENCES public.prompt_hub(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: jolt_project_versions fk3vygr940fmg6y9t5525sjnn9h; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.jolt_project_versions
        ADD CONSTRAINT fk3vygr940fmg6y9t5525sjnn9h FOREIGN KEY (project_id) REFERENCES public.jolt_projects(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: retro_board_columns fk57wvcbe011oq007w32odyvqs8; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.retro_board_columns
        ADD CONSTRAINT fk57wvcbe011oq007w32odyvqs8 FOREIGN KEY (board_id) REFERENCES public.retro_boards(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: retro_card_votes fk829yal49tjedi21d61u8b8cq7; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.retro_card_votes
        ADD CONSTRAINT fk829yal49tjedi21d61u8b8cq7 FOREIGN KEY (card_id) REFERENCES public.retro_cards(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: action_plan_participants fk8uqshjlw0d3q8ascxg0rjds9a; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.action_plan_participants
        ADD CONSTRAINT fk8uqshjlw0d3q8ascxg0rjds9a FOREIGN KEY (board_id) REFERENCES public.action_plans(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: prompt_collection_items fkawpj08nwm852teetdwbm4884g; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.prompt_collection_items
        ADD CONSTRAINT fkawpj08nwm852teetdwbm4884g FOREIGN KEY (collection_id) REFERENCES public.prompt_collections(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: prompt_collection_items fkchhbwa9x5r1y4dula7o0rxsrd; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.prompt_collection_items
        ADD CONSTRAINT fkchhbwa9x5r1y4dula7o0rxsrd FOREIGN KEY (prompt_id) REFERENCES public.prompt_hub(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: api_key_scopes fkkejmbpd6rvsipbx2qpjf7srg1; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.api_key_scopes
        ADD CONSTRAINT fkkejmbpd6rvsipbx2qpjf7srg1 FOREIGN KEY (api_key_id) REFERENCES public.api_keys(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: prompt_comments fko16ux5omqsctvlrtfvjexr6m; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.prompt_comments
        ADD CONSTRAINT fko16ux5omqsctvlrtfvjexr6m FOREIGN KEY (prompt_id) REFERENCES public.prompt_hub(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: knowledge_tags fkonouvudn0oa3wrj0jgpcffrej; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.knowledge_tags
        ADD CONSTRAINT fkonouvudn0oa3wrj0jgpcffrej FOREIGN KEY (document_id) REFERENCES public.knowledge_kb(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

-- Name: app_release_changes fktn29dk1ei2jbkqgm38og0hlhq; Type: FK CONSTRAINT; Schema: public; Owner: -

DO $$ BEGIN
    ALTER TABLE ONLY public.app_release_changes
        ADD CONSTRAINT fktn29dk1ei2jbkqgm38og0hlhq FOREIGN KEY (release_id) REFERENCES public.app_releases(id);
EXCEPTION WHEN duplicate_table OR duplicate_object OR invalid_table_definition THEN NULL; END $$;

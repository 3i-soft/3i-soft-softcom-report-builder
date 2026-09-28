-- softcom-report-builder 1.0.0 - tables in the HOST application's database (PostgreSQL).
-- Copy this script into the application's own Flyway folder as its next version
-- (e.g. generalWarehouse: db/migration/V5_6_4__script.sql). It is idempotent.
DO $SCRIPT$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'rb_report_definition_seq' AND relkind = 'S') THEN
        CREATE SEQUENCE rb_report_definition_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
    END IF;

    CREATE TABLE IF NOT EXISTS rb_report_definition (
        id              bigint                 NOT NULL,
        name            character varying(200) NOT NULL,
        description     character varying(1000),
        datasource_key  character varying(150) NOT NULL,
        definition      text                   NOT NULL,
        owner           character varying(100) NOT NULL,
        shared          boolean                NOT NULL DEFAULT false,
        shared_roles    character varying(1000),
        version         integer,
        created_date    timestamp without time zone,
        created_by      character varying(100),
        updated_date    timestamp without time zone,
        updated_by      character varying(100),
        CONSTRAINT rb_report_definition_pkey PRIMARY KEY (id)
    );
    CREATE INDEX IF NOT EXISTS ix_rb_report_definition_owner ON rb_report_definition (owner);
    CREATE INDEX IF NOT EXISTS ix_rb_report_definition_shared ON rb_report_definition (shared) WHERE shared = true;

    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = 'rb_report_run_log_seq' AND relkind = 'S') THEN
        CREATE SEQUENCE rb_report_run_log_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
    END IF;

    CREATE TABLE IF NOT EXISTS rb_report_run_log (
        id              bigint                 NOT NULL,
        definition_id   bigint,
        datasource_key  character varying(150),
        kind            character varying(20),
        run_by          character varying(100),
        started_at      timestamp without time zone,
        duration_ms     bigint,
        row_count       integer,
        success         boolean,
        error_message   character varying(1000),
        CONSTRAINT rb_report_run_log_pkey PRIMARY KEY (id)
    );
    CREATE INDEX IF NOT EXISTS ix_rb_report_run_log_started ON rb_report_run_log (started_at);
    CREATE INDEX IF NOT EXISTS ix_rb_report_run_log_definition ON rb_report_run_log (definition_id);
END
$SCRIPT$;

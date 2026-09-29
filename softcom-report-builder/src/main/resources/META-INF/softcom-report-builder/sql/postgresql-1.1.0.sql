-- softcom-report-builder 1.1.0 - changes to the HOST application's database (PostgreSQL), after 1.0.0.
-- Copy this script into the application's own Flyway folder as its next version
-- (e.g. generalWarehouse: db/migration/V5_6_5__script.sql). It is idempotent.
DO $SCRIPT$
BEGIN
    -- sharing with specific users, and folders
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'rb_report_definition' AND column_name = 'shared_users') THEN
        ALTER TABLE rb_report_definition ADD COLUMN shared_users character varying(2000);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'rb_report_definition' AND column_name = 'folder') THEN
        ALTER TABLE rb_report_definition ADD COLUMN folder character varying(200);
    END IF;

    -- names entered on the labels page
    CREATE TABLE IF NOT EXISTS rb_label (
        label_key       character varying(300) NOT NULL,
        label_ar        character varying(300),
        label_en        character varying(300),
        updated_by      character varying(100),
        updated_date    timestamp without time zone,
        CONSTRAINT rb_label_pkey PRIMARY KEY (label_key)
    );
END
$SCRIPT$;

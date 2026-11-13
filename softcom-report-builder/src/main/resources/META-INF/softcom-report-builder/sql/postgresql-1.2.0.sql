-- softcom-report-builder 1.2.0 - changes to the HOST application's database (PostgreSQL), after 1.1.0.
-- Run by the library itself when the application starts (ReportSchemaUpdater, recorded in rb_schema_version):
-- do NOT copy it into the application's migrations. It is idempotent.
DO $SCRIPT$
BEGIN
    -- tables and fields hidden on the labels page: not offered in reports
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'rb_label' AND column_name = 'hidden') THEN
        ALTER TABLE rb_label ADD COLUMN hidden boolean NOT NULL DEFAULT false;
    END IF;
END
$SCRIPT$;

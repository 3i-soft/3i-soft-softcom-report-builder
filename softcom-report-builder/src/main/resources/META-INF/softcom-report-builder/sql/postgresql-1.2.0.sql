-- softcom-report-builder 1.2.0 - changes to the HOST application's database (PostgreSQL), after 1.1.0.
-- Copy this script into the application's own Flyway folder as its next version. It is idempotent.
DO $SCRIPT$
BEGIN
    -- tables and fields hidden on the labels page: not offered in reports
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'rb_label' AND column_name = 'hidden') THEN
        ALTER TABLE rb_label ADD COLUMN hidden boolean NOT NULL DEFAULT false;
    END IF;
END
$SCRIPT$;

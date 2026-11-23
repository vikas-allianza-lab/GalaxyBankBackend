-- Notification categories and separate bank-staff inbox.
-- Existing customer inbox MB003001 and campaign table MB003003 are preserved.

ALTER TABLE public."MB003001"
    ADD COLUMN IF NOT EXISTS category VARCHAR(40) NOT NULL DEFAULT 'BANK_ANNOUNCEMENT',
    ADD COLUMN IF NOT EXISTS reference_type VARCHAR(40),
    ADD COLUMN IF NOT EXISTS reference_id VARCHAR(100);

UPDATE public."MB003001"
SET category = 'BANK_ANNOUNCEMENT'
WHERE category IS NULL OR btrim(category) = '';

CREATE INDEX IF NOT EXISTS idx_mb003001_customer_category_created
    ON public."MB003001" (customer_no, category, created_on DESC);

CREATE INDEX IF NOT EXISTS idx_mb003001_customer_read_created
    ON public."MB003001" (customer_no, is_read, created_on DESC);

-- MB003004 is already used by the project for device-reset history.
-- Use MB003005 for the staff inbox. One row per recipient ensures independent read status.
CREATE TABLE IF NOT EXISTS public."MB003005" (
    id BIGSERIAL PRIMARY KEY,
    recipient_user_id BIGINT NOT NULL REFERENCES public."MB001001"(user_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    branch_code VARCHAR(20) REFERENCES public."MB002002"(branch_code)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    category VARCHAR(40) NOT NULL,
    title VARCHAR(120) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    reference_type VARCHAR(40),
    reference_id VARCHAR(100),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT REFERENCES public."MB001001"(user_id)
        ON UPDATE CASCADE ON DELETE SET NULL,
    created_on TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_on TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_mb003005_recipient_read_created
    ON public."MB003005" (recipient_user_id, is_read, created_on DESC);
CREATE INDEX IF NOT EXISTS idx_mb003005_recipient_category_created
    ON public."MB003005" (recipient_user_id, category, created_on DESC);

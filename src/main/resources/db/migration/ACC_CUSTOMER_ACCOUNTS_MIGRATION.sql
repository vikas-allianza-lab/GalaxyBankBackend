
-- ABC Bank ACC-01 through ACC-07 migration
-- Run against the same PostgreSQL database used by Spring Boot.
-- Existing customer/account records are preserved.

BEGIN;

-- 1. Add stable UUID to existing account master table
ALTER TABLE public."MB002003"
    ADD COLUMN IF NOT EXISTS account_id uuid DEFAULT gen_random_uuid();

UPDATE public."MB002003"
SET account_id = gen_random_uuid()
WHERE account_id IS NULL;

ALTER TABLE public."MB002003"
    ALTER COLUMN account_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_mb002003_account_id
    ON public."MB002003" (account_id);

CREATE UNIQUE INDEX IF NOT EXISTS ux_mb002003_customer_account
    ON public."MB002003" (customer_no, account_no);

CREATE INDEX IF NOT EXISTS ix_mb002003_customer_status
    ON public."MB002003" (customer_no, account_status);


-- 2. Account balance integration table
CREATE TABLE IF NOT EXISTS public.mobile_account_balances (
    account_no          varchar(30) PRIMARY KEY,
    customer_no         varchar(30) NOT NULL,
    available_balance   numeric(18,2) NOT NULL,
    ledger_balance      numeric(18,2) NOT NULL,
    currency            varchar(3) NOT NULL DEFAULT 'INR',
    as_of               timestamptz NOT NULL,
    synced_at           timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_mobile_balance_currency
        CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS ix_mobile_account_balances_customer
    ON public.mobile_account_balances (customer_no);


-- 3. Account transaction integration table
CREATE TABLE IF NOT EXISTS public.mobile_account_transactions (
    txn_id          bigserial PRIMARY KEY,
    source_txn_id   varchar(80) UNIQUE,
    customer_no     varchar(30) NOT NULL,
    account_no      varchar(30) NOT NULL,
    title           varchar(160) NOT NULL,
    txn_at          timestamptz NOT NULL,
    amount          numeric(18,2) NOT NULL,
    kind            varchar(20) NOT NULL,
    category        varchar(20),
    status          varchar(20) NOT NULL,
    mode            varchar(30),
    balance_after   numeric(18,2),
    created_at      timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_mobile_txn_kind
        CHECK (kind IN ('CREDIT', 'DEBIT')),

    CONSTRAINT ck_mobile_txn_status
        CHECK (status IN ('SUCCESS', 'PENDING', 'FAILED')),

    CONSTRAINT ck_mobile_txn_amount
        CHECK (amount >= 0)
);

CREATE INDEX IF NOT EXISTS ix_mobile_txn_account_date
    ON public.mobile_account_transactions
    (customer_no, account_no, txn_at DESC, txn_id DESC);

CREATE INDEX IF NOT EXISTS ix_mobile_txn_kind_date
    ON public.mobile_account_transactions
    (customer_no, account_no, category, txn_at DESC);


-- 4. Add category constraint safely
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_mobile_txn_category'
          AND conrelid =
              'public.mobile_account_transactions'::regclass
    ) THEN
        ALTER TABLE public.mobile_account_transactions
        ADD CONSTRAINT ck_mobile_txn_category
        CHECK (
            category IS NULL OR
            category IN (
                'DEPOSIT',
                'WITHDRAWAL',
                'BILL_PAYMENT',
                'UPI'
            )
        );
    END IF;
END $$;


-- 5. Add balance foreign key separately
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_mobile_balance_account'
          AND conrelid =
              'public.mobile_account_balances'::regclass
    ) THEN
        ALTER TABLE public.mobile_account_balances
        ADD CONSTRAINT fk_mobile_balance_account
        FOREIGN KEY (customer_no, account_no)
        REFERENCES public."MB002003" (customer_no, account_no)
        ON UPDATE CASCADE
        ON DELETE CASCADE;
    END IF;
END $$;


-- 6. Add transaction foreign key separately
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_mobile_txn_account'
          AND conrelid =
              'public.mobile_account_transactions'::regclass
    ) THEN
        ALTER TABLE public.mobile_account_transactions
        ADD CONSTRAINT fk_mobile_txn_account
        FOREIGN KEY (customer_no, account_no)
        REFERENCES public."MB002003" (customer_no, account_no)
        ON UPDATE CASCADE
        ON DELETE CASCADE;
    END IF;
END $$;


-- 7. Account reveal access audit table
CREATE TABLE IF NOT EXISTS public.mobile_account_access_audit (
    audit_id        bigserial PRIMARY KEY,
    customer_no     varchar(30) NOT NULL,
    account_id      uuid NOT NULL,
    action          varchar(30) NOT NULL,
    ip_address      varchar(64),
    accessed_at     timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_mobile_account_access_audit_customer
    ON public.mobile_account_access_audit
    (customer_no, accessed_at DESC);


COMMIT;

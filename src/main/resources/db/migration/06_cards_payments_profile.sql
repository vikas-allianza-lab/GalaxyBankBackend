-- Additive migration for Cards (CRD-01..10), Payments (PAY-01..05), and PRO-01.
-- Does not alter or drop existing application tables.

CREATE TABLE IF NOT EXISTS public.cards (
    card_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    processor_card_ref VARCHAR(120) NOT NULL UNIQUE,
    customer_no VARCHAR(30) NOT NULL REFERENCES public.mb001001cr(customer_id),
    account_no VARCHAR(30) NOT NULL,
    card_type VARCHAR(20) NOT NULL CHECK (card_type IN ('DEBIT','CREDIT')),
    masked_number VARCHAR(32) NOT NULL CHECK (masked_number ~ '^[*Xx• -]*[0-9]{4}$'),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','BLOCKED')),
    network VARCHAR(30),
    block_type VARCHAR(20) CHECK (block_type IS NULL OR block_type IN ('TEMPORARY','PERMANENT')),
    created_on TIMESTAMPTZ NOT NULL DEFAULT now(),
    modified_on TIMESTAMPTZ,
    CONSTRAINT fk_cards_account_customer FOREIGN KEY (account_no, customer_no)
      REFERENCES public."MB002003"(account_no, customer_no) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT ck_cards_block_state CHECK ((status='BLOCKED' AND block_type IS NOT NULL) OR (status='ACTIVE' AND block_type IS NULL))
);
CREATE INDEX IF NOT EXISTS ix_cards_customer ON public.cards(customer_no, created_on DESC);

CREATE TABLE IF NOT EXISTS public.card_limits (
    id BIGSERIAL PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES public.cards(card_id) ON DELETE CASCADE,
    limit_key VARCHAR(20) NOT NULL CHECK (limit_key IN ('ATM','POS','ONLINE','INTERNATIONAL')),
    min_value NUMERIC(18,2) NOT NULL,
    max_value NUMERIC(18,2) NOT NULL,
    step_value NUMERIC(18,2) NOT NULL,
    current_value NUMERIC(18,2) NOT NULL,
    per_transaction_limit NUMERIC(18,2) NOT NULL DEFAULT 0,
    default_value NUMERIC(18,2) NOT NULL,
    modified_on TIMESTAMPTZ,
    UNIQUE(card_id, limit_key),
    CHECK (min_value >= 0 AND max_value >= min_value AND step_value > 0),
    CHECK (current_value BETWEEN min_value AND max_value),
    CHECK (per_transaction_limit >= 0 AND per_transaction_limit <= current_value),
    CHECK (default_value BETWEEN min_value AND max_value)
);

CREATE TABLE IF NOT EXISTS public.card_events (
    event_id BIGSERIAL PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES public.cards(card_id) ON DELETE RESTRICT,
    customer_no VARCHAR(30) NOT NULL REFERENCES public.mb001001cr(customer_id),
    event_type VARCHAR(30) NOT NULL,
    reason VARCHAR(80),
    block_type VARCHAR(20),
    event_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by BIGINT NOT NULL REFERENCES public."MB001001"(user_id),
    created_on TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_card_events_card_date ON public.card_events(card_id, created_on DESC);

CREATE TABLE IF NOT EXISTS public.card_pin_otps (
    otp_id BIGSERIAL PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES public.cards(card_id) ON DELETE CASCADE,
    customer_no VARCHAR(30) NOT NULL REFERENCES public.mb001001cr(customer_id),
    otp_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempts SMALLINT NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_card_pin_otp_active ON public.card_pin_otps(customer_no, card_id, sent_at DESC) WHERE consumed_at IS NULL;

CREATE TABLE IF NOT EXISTS public.card_requests (
    request_id BIGSERIAL PRIMARY KEY,
    customer_no VARCHAR(30) NOT NULL REFERENCES public.mb001001cr(customer_id),
    account_no VARCHAR(30) NOT NULL,
    name_on_card VARCHAR(26) NOT NULL,
    delivery_preference VARCHAR(20) NOT NULL CHECK (delivery_preference IN ('DEFAULT','PRIMARY','BRANCH')),
    delivery_address VARCHAR(500),
    branch_code VARCHAR(20) REFERENCES public."MB002002"(branch_code),
    terms_accepted BOOLEAN NOT NULL CHECK (terms_accepted = TRUE),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED','COMPLETED')),
    created_by BIGINT NOT NULL REFERENCES public."MB001001"(user_id),
    created_on TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_card_request_account_customer FOREIGN KEY (account_no, customer_no)
      REFERENCES public."MB002003"(account_no, customer_no) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT ck_card_request_delivery CHECK (
      (delivery_preference = 'BRANCH' AND branch_code IS NOT NULL)
      OR (delivery_preference <> 'BRANCH')
    )
);
CREATE INDEX IF NOT EXISTS ix_card_requests_customer ON public.card_requests(customer_no, created_on DESC);

CREATE TABLE IF NOT EXISTS public.card_transactions (
    transaction_id BIGSERIAL PRIMARY KEY,
    card_id UUID NOT NULL REFERENCES public.cards(card_id) ON DELETE RESTRICT,
    processor_txn_ref VARCHAR(100) UNIQUE,
    title VARCHAR(160) NOT NULL,
    merchant VARCHAR(160),
    amount NUMERIC(18,2) NOT NULL CHECK (amount >= 0),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUCCESS','PENDING','FAILED','REVERSED')),
    txn_at TIMESTAMPTZ NOT NULL,
    created_on TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_card_txn_card_date ON public.card_transactions(card_id, txn_at DESC, transaction_id DESC);

CREATE TABLE IF NOT EXISTS public.mobile_billers (
    biller_id BIGSERIAL PRIMARY KEY,
    partner_biller_code VARCHAR(100) NOT NULL UNIQUE,
    category VARCHAR(40) NOT NULL,
    biller_name VARCHAR(160) NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    synced_on TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ix_mobile_billers_category ON public.mobile_billers(category, status, biller_name);

CREATE TABLE IF NOT EXISTS public.mobile_payment_requests (
    request_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no VARCHAR(30) NOT NULL REFERENCES public.mb001001cr(customer_id),
    account_no VARCHAR(30) NOT NULL,
    payment_type VARCHAR(20) NOT NULL CHECK (payment_type IN ('BILL','RECHARGE','UPI')),
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    partner_reference VARCHAR(120),
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    created_on TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mobile_payment_idempotency UNIQUE(customer_no, idempotency_key),
    CONSTRAINT fk_mobile_payment_account_customer FOREIGN KEY (account_no, customer_no)
      REFERENCES public."MB002003"(account_no, customer_no) ON UPDATE CASCADE ON DELETE RESTRICT
);
CREATE INDEX IF NOT EXISTS ix_mobile_payment_customer_date ON public.mobile_payment_requests(customer_no, created_on DESC);

CREATE TABLE IF NOT EXISTS public.mobile_indian_states (
    state_name VARCHAR(80) PRIMARY KEY,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO public.mobile_indian_states(state_name) VALUES
('Andhra Pradesh'),('Arunachal Pradesh'),('Assam'),('Bihar'),('Chhattisgarh'),('Goa'),('Gujarat'),('Haryana'),('Himachal Pradesh'),('Jharkhand'),('Karnataka'),('Kerala'),('Madhya Pradesh'),('Maharashtra'),('Manipur'),('Meghalaya'),('Mizoram'),('Nagaland'),('Odisha'),('Punjab'),('Rajasthan'),('Sikkim'),('Tamil Nadu'),('Telangana'),('Tripura'),('Uttar Pradesh'),('Uttarakhand'),('West Bengal'),('Andaman and Nicobar Islands'),('Chandigarh'),('Dadra and Nagar Haveli and Daman and Diu'),('Delhi'),('Jammu and Kashmir'),('Ladakh'),('Lakshadweep'),('Puducherry')
ON CONFLICT (state_name) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.mobile_customer_profile (
    customer_no VARCHAR(30) PRIMARY KEY REFERENCES public.mb001001cr(customer_id),
    gender VARCHAR(10) NOT NULL CHECK (gender IN ('Female','Male','Other')),
    address VARCHAR(300) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(80) NOT NULL REFERENCES public.mobile_indian_states(state_name),
    pincode VARCHAR(6) NOT NULL CHECK (pincode ~ '^[0-9]{6}$'),
    modified_by BIGINT NOT NULL REFERENCES public."MB001001"(user_id),
    modified_on TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.mobile_customer_profile_audit (
    audit_id BIGSERIAL PRIMARY KEY,
    customer_no VARCHAR(30) NOT NULL REFERENCES public.mb001001cr(customer_id),
    old_values JSONB NOT NULL,
    new_values JSONB NOT NULL,
    changed_by BIGINT NOT NULL REFERENCES public."MB001001"(user_id),
    changed_on TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_mobile_profile_audit_customer ON public.mobile_customer_profile_audit(customer_no, changed_on DESC);

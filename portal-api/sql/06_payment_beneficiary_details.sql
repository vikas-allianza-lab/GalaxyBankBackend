-- Beneficiary branch name and mobile number for RTGS / NEFT / IMPS requests (MB004001).
-- Printed on the verified payment PDF under "Beneficiary Details". Both columns are optional.
ALTER TABLE public."MB004001" ADD COLUMN IF NOT EXISTS ben_branch VARCHAR(150);
ALTER TABLE public."MB004001" ADD COLUMN IF NOT EXISTS ben_mobile VARCHAR(15);

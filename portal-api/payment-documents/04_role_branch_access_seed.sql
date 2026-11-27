-- Allianza Portal: Super Admin / Bank Admin / Bank User + Maker / Checker seed
-- Run once against the supplied PostgreSQL database. Safe to re-run.
-- Demo credentials created below: superadmin / ChangeMe123!
-- Change this password immediately after first login. Staff demo accounts use the same temporary password.

BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Keep Customer (CU) role markers exactly as requested.
UPDATE public."MB001001"
   SET user_role = 'no', sys_role = 'no'
 WHERE user_type = 'CU';

-- Normalize existing portal users. ADMIN is the platform Super Admin.
UPDATE public."MB001001"
   SET user_role = 'Super Admin', sys_role = 'Admin'
 WHERE user_type = 'ADMIN';

UPDATE public."MB001001"
   SET user_role = CASE
       WHEN lower(coalesce(user_role,'')) LIKE '%admin%' THEN 'Bank Admin'
       ELSE 'Bank User'
   END,
   sys_role = CASE WHEN lower(coalesce(sys_role,'')) = 'checker' THEN 'Checker' ELSE 'Maker' END
 WHERE user_type = 'STAFF';

-- Ensure there is a bank master record. Existing real bank records are preserved.
DO $$
DECLARE v_bank_code varchar(20);
BEGIN
  SELECT bank_code INTO v_bank_code FROM public."MB002001" ORDER BY bank_code LIMIT 1;
  IF v_bank_code IS NULL THEN
    v_bank_code := 'ALLIANZA-DEMO';
    INSERT INTO public."MB002001" (bank_code, bank_name, bank_type, ifsc_code, city, state)
    VALUES (v_bank_code, 'Allianza Demo Bank', 'Co-operative Bank', 'ALLI0000001', 'Pune', 'Maharashtra')
    ON CONFLICT DO NOTHING;
  END IF;

  -- Add eight demo branches without overwriting any existing real branches.
  INSERT INTO public."MB002002" (branch_code, bank_code, branch_name, ifsc_code, br_city, br_state)
  SELECT ('ALZ' || lpad(n::text,3,'0')), v_bank_code,
         CASE WHEN n=1 THEN 'Main Branch' ELSE format('Branch %s', n) END,
         ('ALLI' || lpad(n::text,7,'0')), 'Pune', 'Maharashtra'
    FROM generate_series(1,8) AS n
   WHERE NOT EXISTS (SELECT 1 FROM public."MB002002" b WHERE b.branch_code=('ALZ' || lpad(n::text,3,'0')));
END $$;

-- Mirror a demo bank/branch set in the normalized banks / branches tables, if those tables are present.
DO $$
DECLARE v_bank_id bigint;
BEGIN
  IF to_regclass('public.banks') IS NOT NULL AND to_regclass('public.branches') IS NOT NULL THEN
    SELECT id INTO v_bank_id FROM public.banks ORDER BY id LIMIT 1;
    IF v_bank_id IS NULL THEN
      SELECT coalesce(max(id),0)+1 INTO v_bank_id FROM public.banks;
      INSERT INTO public.banks(id, bank_code, bank_name, bank_type, status)
      VALUES (v_bank_id, 'ALLIANZA-DEMO', 'Allianza Demo Bank', 'Co-operative Bank', 'ACTIVE');
    END IF;
    INSERT INTO public.branches(id, bank_id, branch_code, branch_name, city, state, country, ifsc_code, status)
    SELECT (SELECT coalesce(max(id),0) FROM public.branches) + row_number() over (ORDER BY n),
           v_bank_id, ('ALZ' || lpad(n::text,3,'0')),
           CASE WHEN n=1 THEN 'Main Branch' ELSE format('Branch %s', n) END,
           'Pune', 'Maharashtra', 'India', ('ALLI' || lpad(n::text,7,'0')), 'ACTIVE'
      FROM generate_series(1,8) AS n
     WHERE NOT EXISTS (SELECT 1 FROM public.branches b WHERE b.branch_code=('ALZ' || lpad(n::text,3,'0')));
  END IF;
END $$;


-- Built-in role catalogue entries for the portal's Manage Access screen.
INSERT INTO public.portal_access_roles(id, role_name, description, active, system_role, permissions)
SELECT v.id, v.role_name, v.description, true, true, '[]'::jsonb
  FROM (VALUES
    ('BASE-SUPER-ADMIN', 'Super Admin', 'Full platform administration across all banks and branches.'),
    ('BASE-BANK-ADMIN', 'Bank Admin', 'Bank staff role; branch-scoped and controlled by Maker or Checker system role.'),
    ('BASE-BANK-USER', 'Bank User', 'Bank user role; branch-scoped and controlled by Maker or Checker system role.')
  ) AS v(id, role_name, description)
 WHERE NOT EXISTS (SELECT 1 FROM public.portal_access_roles r WHERE lower(r.role_name)=lower(v.role_name));

-- Demo Super Admin + four STAFF role combinations. Existing users are not overwritten.
WITH branch_rows AS (
  SELECT branch_code, row_number() OVER (ORDER BY branch_code) AS rn
    FROM public."MB002002" WHERE branch_code LIKE 'ALZ%'
), seed AS (
  SELECT * FROM (VALUES
    ('DEMO-SA-001','Super','Admin','superadmin','Super Admin','Admin',1,'superadmin@allianza.demo','9000000001'),
    ('DEMO-BA-MK','Bank','Admin Maker','bankadmin.maker','Bank Admin','Maker',1,'bankadmin.maker@allianza.demo','9000000002'),
    ('DEMO-BA-CK','Bank','Admin Checker','bankadmin.checker','Bank Admin','Checker',2,'bankadmin.checker@allianza.demo','9000000003'),
    ('DEMO-BU-MK','Bank','User Maker','bankuser.maker','Bank User','Maker',3,'bankuser.maker@allianza.demo','9000000004'),
    ('DEMO-BU-CK','Bank','User Checker','bankuser.checker','Bank User','Checker',4,'bankuser.checker@allianza.demo','9000000005')
  ) AS v(emp_no,first_name,last_name,user_name,user_role,sys_role,branch_rn,email,mobile)
), inserted AS (
  INSERT INTO public."MB001001"
    (emp_no,title,user_type,first_name,last_name,designation,dob,email,mobile,user_name,disp_name,user_role,sys_role,branch,status,multi_branch,idle_timeout,"2fa_login",authentication,created_by)
  SELECT s.emp_no,'Mr.',CASE WHEN s.user_role='Super Admin' THEN 'ADMIN' ELSE 'STAFF' END,
         s.first_name,s.last_name,'Portal User',DATE '1990-01-01',s.email,s.mobile,s.user_name,
         s.first_name || ' ' || s.last_name,s.user_role,s.sys_role,
         COALESCE((SELECT branch_code FROM branch_rows WHERE rn=s.branch_rn),(SELECT branch_code FROM branch_rows ORDER BY rn LIMIT 1)),
         'ACTIVE',false,180,true,'Password + OTP','1'
    FROM seed s
   WHERE NOT EXISTS (SELECT 1 FROM public."MB001001" u WHERE u.user_name=s.user_name OR u.emp_no=s.emp_no)
  RETURNING user_id,user_name
)
INSERT INTO public."MB001002" (user_id,password_hash,must_reset,created_by)
SELECT i.user_id, crypt('ChangeMe123!', gen_salt('bf', 10)), true, i.user_id
  FROM inserted i;

-- Link seeded STAFF users to employee master records where the normalized tables exist.
DO $$
BEGIN
  IF to_regclass('public.employees') IS NOT NULL AND to_regclass('public.branches') IS NOT NULL THEN
    INSERT INTO public.employees(id, branch_id, employee_no, full_name, date_of_birth, mobile_no, email, designation, employment_status)
    SELECT (SELECT coalesce(max(id),0) FROM public.employees) + row_number() OVER (ORDER BY u.emp_no),
           b.id, u.emp_no, u.full_name, u.dob, u.mobile, u.email, u.designation, 'ACTIVE'
      FROM public."MB001001" u
      JOIN public.branches b ON b.branch_code=u.branch
     WHERE u.user_type IN ('STAFF','ADMIN')
       AND u.emp_no LIKE 'DEMO-%'
       AND NOT EXISTS (SELECT 1 FROM public.employees e WHERE e.employee_no=u.emp_no);
  END IF;
END $$;

COMMIT;

-- Created demo logins:
-- superadmin / ChangeMe123!         (Super Admin / Admin)
-- bankadmin.maker / ChangeMe123!    (Bank Admin / Maker)
-- bankadmin.checker / ChangeMe123!  (Bank Admin / Checker)
-- bankuser.maker / ChangeMe123!     (Bank User / Maker)
-- bankuser.checker / ChangeMe123!   (Bank User / Checker)
-- All demo accounts are marked must_reset=true in MB001002; enforce a password reset before production use.

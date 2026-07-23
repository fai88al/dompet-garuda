-- Admin/writer accounts for the backoffice panel (FR15, CLAUDE.md §4).
-- Passwords are BCrypt-hashed at cost 10. Plaintext temporary passwords are documented
-- only in the PR description of feat/admin-user-auth — never in committed files (§7.9).
-- Rotate both passwords immediately after confirming login works.

CREATE TABLE admin_users (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(120)  UNIQUE NOT NULL,
    password_hash VARCHAR(255)  NOT NULL,
    role          VARCHAR(20)   NOT NULL CHECK (role IN ('ADMIN', 'WRITER')),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Seeded accounts (role ADMIN). Rotate passwords on first login.
INSERT INTO admin_users (username, password_hash, role) VALUES
    ('rizki@dompetgaruda.com',  '$2a$10$NpOVAQfL16CgUABz2TlWnOdxN4O.q7ZjNXQY.FJU7Jt2JGR.VFuza', 'ADMIN'),
    ('faisal@dompetgaruda.com', '$2a$10$Y.icFc93yONom7QYcrXKFejGlF3xtmomSITqNy6DM7F6BO4DuN2Q.', 'ADMIN');

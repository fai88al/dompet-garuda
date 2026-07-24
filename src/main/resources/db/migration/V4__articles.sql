-- Articles feature: backoffice-authored content (DRAFT/PUBLISHED), plus a new WRITER
-- account for content editors alongside the existing ADMIN accounts (CLAUDE.md §4).
-- Temporary password documented only in the PR description — never in committed files (§7.9).

CREATE TABLE articles (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    title            VARCHAR(255)  NOT NULL,
    slug             VARCHAR(255)  UNIQUE NOT NULL,
    content_html     TEXT          NOT NULL,
    cover_image_url  TEXT,
    status           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED')),
    author_id        UUID          NOT NULL REFERENCES admin_users(id),
    published_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_articles_status ON articles(status);
CREATE INDEX idx_articles_slug ON articles(slug);

-- Seeded WRITER account for backoffice article authoring. Rotate password on first login.
INSERT INTO admin_users (username, password_hash, role) VALUES
    ('rizkiwriter@dompetgaruda.com', '$2a$10$JKa/3KW7wsfjIrLkTmfiBeNvdJUJO25MEVhMnKOi.Zg/1DnN6Y3be', 'WRITER');

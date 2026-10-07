# WePick Repository Instructions

For work that affects product behavior, UX, domain models, APIs, authentication, moderation, AI topics, data design, or delivery scope, use `$wepick-product-context` before planning or editing.

Treat the parent `wepick-product` repository (`..`) as the source of product intent. Keep backend-specific execution, testing, migrations, generated OpenAPI, and implementation details in this repository. Do not duplicate Product documents here.

Follow the commit and PR conventions in `../docs/guides/commit-convention.md` and `../docs/guides/pull-request-convention.md` (Product repository). Use `$wepick-commit` when asked to commit or open a PR.

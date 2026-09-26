---
status: accepted
date: 2026-09-26
---

# Security Review Process

## Context

Security-sensitive changes (auth, encryption, data export, credential storage) need explicit security review. This ADR defines what requires review, who performs it, and the review checklist.

## Decision

### When security review is required

| Change type | Example | Reviewer |
|---|---|---|
| New credential storage | Adding a new `SecureStorage` field | Senior engineer |
| Auth flow change | OAuth, token refresh, session management | Senior engineer |
| Data export | Backup file format, export API | Senior engineer |
| Encryption change | New cipher, key rotation | Security-focused engineer |
| Permission change | New Android permission, permission escalation | Senior engineer |

### Security review checklist

1. **Credential handling** — no credentials in logs, no credentials in Room, no credentials in DataStore without encryption
2. **Token storage** — tokens stored in `SecureStoragePort`, not plain DataStore or Room
3. **No PII in logs** — user content (task text, note text) never appears in logs
4. **Input validation** — all user input is validated before use
5. **SQL injection** — Room prevents this, but raw SQL queries are forbidden
6. **Backup security** — backup files are encrypted with user-derived key
7. **No secret hardcoding** — API keys never in code; always from `SecureStoragePort`

### Who performs review

- For this project: any senior engineer or the designated security contact
- For externally-facing changes (network API, OAuth): external security review is recommended

### Process

1. Author flags the PR with `🔒 security-sensitive` label
2. Reviewer completes the checklist above
3. If any item fails: reviewer blocks with specific finding
4. Author fixes or justifies the exception
5. Reviewer approves

## Consequences

- `security-review` skill gives a step-by-step security review checklist
- Security-sensitive changes are now explicitly flagged in PRs
- `singularity-todo-secure-storage` skill governs credential storage decisions

# Local Agent Rules Versioning

## Purpose
This project keeps `AGENTS.md` and `agent-rules/` out of the shared repository history.
They are versioned locally only, using a separate git directory that does not affect the main repo.

## What this means
- The main repository still ignores `AGENTS.md` and `agent-rules/`.
- `git pull`, `git status`, and `git push` in the main repository do not manage this history.
- Local agent-rules history lives in:
  - `agent-rules/.local-git`
- No remote is configured by default.
- Nothing from this local history is shared unless a remote is intentionally added later.

## Wrapper command
Use:

`agent-rules/local_git.sh <git-args>`

Examples:
- `agent-rules/local_git.sh status`
- `agent-rules/local_git.sh log --oneline --decorate -5`
- `agent-rules/local_git.sh diff HEAD~1 HEAD -- AGENTS.md agent-rules`
- `agent-rules/local_git.sh rev-parse --short HEAD`

## Files tracked in local history
- `AGENTS.md`
- top-level operational docs in `agent-rules/`
- helper scripts used for local agent-rules versioning

Ticket artifacts are not automatically tracked in this local history unless you explicitly add them.

## Snapshot workflow
After changing the rules:

1) Review local history status:
   - `agent-rules/local_git.sh status`
2) Stage the managed files:
   - `agent-rules/local_git.sh add AGENTS.md agent-rules/*.md agent-rules/*.sh`
3) Commit:
   - `agent-rules/local_git.sh commit -m "agent-rules: describe change"`
4) Optional tag:
   - `agent-rules/local_git.sh tag agent-rules-YYYYMMDD-HHMM`

## Restore workflow
To inspect an older version:
- `agent-rules/local_git.sh log --oneline --decorate`
- `agent-rules/local_git.sh show <commit>:AGENTS.md`

To restore tracked files from an older version into the working tree:
- `agent-rules/local_git.sh checkout <commit> -- AGENTS.md agent-rules`

## Recording the active rules version in ticket docs
Ticket-generated markdown artifacts should include:
- `Agent Rules Version: <commit-sha-or-tag>`
- `Agent Rules Entry Point: AGENTS.md`

Preferred command to get the current version:
- `agent-rules/local_git.sh rev-parse --short HEAD`

If the local versioning repo has not been initialized yet, use:
- `Agent Rules Version: unversioned`

# WearTAK-CIV Agent Instructions (Codex)

Use selection and global rules from `agent-rules/AGENT_INDEX.md` before proceeding.

## Always read first (mandatory)
1) `agent-rules/WEARTAK_CONTEXT.md`
2) Raw source ticket using this resolution order:
  - `agent-rules/tickets/<TICKET_ID>/TICKET.md` (preferred)
  - `agent-rules/tickets/<TICKET_ID>/<TICKET_ID>.md` (fallback)
  - If neither local file exists, bootstrap `agent-rules/tickets/<TICKET_ID>/TICKET.md` from Jira via `zsh -lic 'details <TICKET_ID>'`
  - Explicit raw ticket path provided by user prompt
3) `agent-rules/tickets/<TICKET_ID>/CONTEXT_EXPANSION.md` (if present)
4) `agent-rules/tickets/<TICKET_ID>/TICKET_REWRITE.md` (if present)
5) `agent-rules/tickets/<TICKET_ID>/SOLUTION_DESIGN.md` (if present)

The ticket file is an approved architecture contract.
Implement it exactly as written. Do not redesign.

## Ticket artifact I/O (mandatory)
- The resolved raw source ticket file remains input-only and must not be overwritten.
- Implement against the approved design artifacts when present.
- Hard gate: do not implement unless solution artifact includes explicit user token `APPROVED FOR IMPLEMENTATION`.

## Branching (mandatory)
- Work only on branch: agent/<TICKET_ID>
- If not already on that branch, create it from the current develop and checkout.

## Contract enforcement (hard rules)
- Do NOT introduce new architecture patterns.
- Do NOT refactor unrelated code.
- Do NOT delete legacy paths unless the ticket explicitly says to (comment out only if instructed).
- If anything is ambiguous, search the repo for an existing pattern and mirror it.
- If still unclear, STOP and ask.

## Execution protocol (hard rule)
Before editing any code:
1) Print an implementation plan.
2) Map each step to the ticket’s success criteria.
3) List exact files you will create/modify.
4) Confirm `APPROVED FOR IMPLEMENTATION` exists in `SOLUTION_DESIGN.md`; if missing, stop and ask user for approval.

Do not begin coding until the plan is printed.

## Implementation constraints
- Do not invent package names or imports. Use repo search to find correct ones.
- Prefer copying patterns from existing files (especially in data/secure and DataRepository).
- Keep diffs minimal.

## Required build commands
- Debug:   ./gradlew clean :app:assembleDebug
- Release: ./gradlew clean :app:assembleRelease (run once at the end)

## Commit policy
- Commit only after a successful build.
- Prefer small milestone commits.
- Include the ticket id in the commit message (e.g., "WEAR-385: ...").

## Handoff to post-implementation
- Once implementation and testing are complete, stop and wait for explicit user approval token `APPROVED TO FINISH TICKET`.
- Do not perform final rebasing, branch normalization, PR creation, Sonar checks, or Jira stage transitions in this phase.
- Those actions belong only to `agent-rules/POST_IMPLEMENTATION_AGENTS.md`.
- When implementation updates ticket-owned markdown artifacts, record the active local agent-rules version in those artifacts.

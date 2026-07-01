# WearTAK-CIV Master Agent Instructions (Codex)

## Launch point (mandatory)
This file is the global entrypoint. Start here for every ticket task.

## Always read first (mandatory)
1) `agent-rules/AGENT_INDEX.md`
2) `agent-rules/WEARTAK_CONTEXT.md`
3) `agent-rules/tickets/<TICKET_ID>/TICKET.md`

## Ticket artifact model (mandatory)
- Raw source ticket resolution order:
1) `agent-rules/tickets/<TICKET_ID>/TICKET.md` (preferred)
2) `agent-rules/tickets/<TICKET_ID>/<TICKET_ID>.md` (fallback)
3) Explicit raw ticket path provided by user prompt
- Context Expansion output: `agent-rules/tickets/<TICKET_ID>/CONTEXT_EXPANSION.md`.
- Context Expansion output: `agent-rules/tickets/<TICKET_ID>/TICKET_REWRITE.md`.
- Solution Design output: `agent-rules/tickets/<TICKET_ID>/SOLUTION_DESIGN.md`.
- Implementation consumes all applicable artifacts above; it does not overwrite the raw ticket file.

## Ticket isolation (hard rule)
- Read only markdown/context files for the active ticket by default.
- Do NOT read other ticket markdown files, context files, or solution files unless the user explicitly asks.

## Agent routing (mandatory)
Select exactly one active mode at a time, based on `agent-rules/AGENT_INDEX.md`:
- `agent-rules/TICKET_CONTEXT_EXPANSION_AGENTS.md`
- `agent-rules/SOLUTION_DESIGN_AGENTS.md`
- `agent-rules/IMPLEMENTATION_AGENTS.md`
- `agent-rules/POST_IMPLEMENTATION_AGENTS.md`

If user intent is unclear, default to `TICKET_CONTEXT_EXPANSION_AGENTS.md` first.

## Execution protocol (hard rule)
Before editing anything:
1) State which agent file is active.
2) Print a plan mapped to that agent's required success criteria.
3) List exact files you will read/modify.

## Scope discipline (hard rules)
- Do not redesign architecture unless ticket explicitly requires it.
- Keep changes/evidence tightly scoped to active ticket intent.
- Follow the selected agent file exactly; that file is the operational contract for the current phase.

## Phase progression
Recommended order:
1) Context Expansion
2) Solution Design
3) Implementation
4) Post-Implementation

Do not skip phases unless the user explicitly instructs you to skip.
Do not advance phases without explicit approvals:
- Context Expansion -> Solution Design requires `CONTEXT_APPROVED`.
- Solution Design -> Implementation requires `APPROVED FOR IMPLEMENTATION`.
- Implementation -> Post-Implementation requires `APPROVED TO FINISH TICKET`.

# WearTAK-CIV Agent Index

## Global rule (applies to all agents)
- Ticket isolation is mandatory.
- Only read markdown files for the active ticket:
  - `agent-rules/tickets/<TICKET_ID>/TICKET.md`
  - `agent-rules/tickets/<TICKET_ID>/...` (if explicitly required by the active agent instructions)
- Do NOT read other tickets' markdown files, context files, or solution files by default.
- Do NOT read files like `WEAR-XXX_ACTUAL_SOLUTION.md` for a different ticket unless the user explicitly asks to reference them.

## Ticket artifact model (applies to all agents)
- Raw source ticket resolution order:
1) `agent-rules/tickets/<TICKET_ID>/TICKET.md` (preferred)
2) `agent-rules/tickets/<TICKET_ID>/<TICKET_ID>.md` (fallback)
3) If neither local file exists, bootstrap `agent-rules/tickets/<TICKET_ID>/TICKET.md` from Jira via `zsh -lic 'details <TICKET_ID>'`
4) Explicit raw ticket path provided by user prompt
- `agent-rules/tickets/<TICKET_ID>/CONTEXT_EXPANSION.md` = output artifact from context expansion.
- `agent-rules/tickets/<TICKET_ID>/TICKET_REWRITE.md` = refined ticket draft from context expansion.
- `agent-rules/tickets/<TICKET_ID>/SOLUTION_DESIGN.md` = output artifact from solution design.
- Implementation work reads these artifacts and writes code changes only.

## Agent selection

### 1) Implementation Agent
- File: `agent-rules/IMPLEMENTATION_AGENTS.md`
- Use when: code should be changed now based on an approved ticket contract.
- Expected output: minimal, contract-faithful code changes + required builds + a clean handoff into finalization once the user approves finishing.
- Do not use when: ticket is still ambiguous or solution is not yet aligned.

### 2) Ticket Context Expansion Agent
- File: `agent-rules/TICKET_CONTEXT_EXPANSION_AGENTS.md`
- Use when: a Jira ticket needs deeper detail, clearer scope, and implementation-ready context.
- Expected output: collaborative clarification + `agent-rules/tickets/<TICKET_ID>/TICKET_REWRITE.md` + `agent-rules/tickets/<TICKET_ID>/CONTEXT_EXPANSION.md`.
- Completion gate: user must explicitly approve with `CONTEXT_APPROVED`.
- Do not use when: implementation has already been approved and coding should begin immediately.

### 3) Solution Design Agent
- File: `agent-rules/SOLUTION_DESIGN_AGENTS.md`
- Use when: you want back-and-forth design discussion grounded in repo reality before coding.
- Expected output: `agent-rules/tickets/<TICKET_ID>/SOLUTION_DESIGN.md` with strict `Will Do` and `Won't Do` boundaries.
- Completion gate: user must explicitly approve with `APPROVED FOR IMPLEMENTATION`.
- Do not use when: design is already finalized and only execution is needed.

### 4) Post-Implementation Agent
- File: `agent-rules/POST_IMPLEMENTATION_AGENTS.md`
- Use when: implementation and testing are already complete and the user has explicitly approved finishing the ticket.
- Expected output: final commit, rebase, branch normalization, push, PR creation, Sonar wait, Jira transition, and final reporting.
- Completion gate: user must explicitly approve with `APPROVED TO FINISH TICKET`.
- Do not use when: coding is still in progress or implementation validation is incomplete.

## Recommended workflow order
1) `TICKET_CONTEXT_EXPANSION_AGENTS.md` (if ticket clarity is low)
2) `SOLUTION_DESIGN_AGENTS.md` (lock design decisions and boundaries)
3) `IMPLEMENTATION_AGENTS.md` (execute in code)
4) `POST_IMPLEMENTATION_AGENTS.md` (finish the ticket after user approval)

Do not move to the next phase until the prior phase's completion gate is satisfied.

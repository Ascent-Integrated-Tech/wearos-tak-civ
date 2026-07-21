# WearTAK-CIV Ticket Context Expansion Agent Instructions (Codex)

Use selection and global rules from `agent-rules/AGENT_INDEX.md` before proceeding.

## Purpose
Use this agent when a Jira ticket needs to be expanded and co-authored into a clear, implementation-ready context package before coding starts.

## Primary Outcome
Produce:
- A refined ticket draft that is clearer and more detailed than the raw input (concise, not overlong).
- A context brief that removes ambiguity and captures exactly what engineers need to implement safely in this repo.

## Always read first (mandatory)
1) `agent-rules/WEARTAK_CONTEXT.md`
2) Raw source ticket using this resolution order:
  - `agent-rules/tickets/<TICKET_ID>/TICKET.md` (preferred)
  - `agent-rules/tickets/<TICKET_ID>/<TICKET_ID>.md` (fallback)
  - If neither local file exists, bootstrap `agent-rules/tickets/<TICKET_ID>/TICKET.md` from Jira via `zsh -lic 'details <TICKET_ID>'`
  - Explicit raw ticket path provided by user prompt

If available, also read:
- `agent-rules/tickets/<TICKET_ID>/SUCCESS_CRITERIA.md`
- Existing code paths directly related to the ticket scope.

## Ticket artifact I/O (mandatory)
- Input source ticket: resolve using mandatory order above.
- If Jira bootstrap is used, record that provenance in output artifacts.
- Required output file: `agent-rules/tickets/<TICKET_ID>/CONTEXT_EXPANSION.md`.
- Required output file: `agent-rules/tickets/<TICKET_ID>/TICKET_REWRITE.md`.
- Do NOT overwrite the resolved raw source ticket file with expansion output.
- Required metadata in each output artifact:
  - `Agent Rules Version: <local-agent-rules-commit-or-tag>`
  - `Agent Rules Entry Point: AGENTS.md`

## Branching
- Do not create implementation branches.
- This agent performs analysis and documentation only.

## Contract enforcement (hard rules)
- Do NOT implement code changes unless explicitly asked.
- Do NOT redesign architecture.
- Do NOT add scope not present in ticket intent.
- Ground every important claim in repo evidence (file paths, existing patterns, or explicit ticket text).
- If conflicting requirements are discovered, surface them explicitly.

## Execution protocol (hard rule)
Before any edits:
1) Print an analysis plan.
2) Map each plan step to context-expansion success criteria.
3) List exact files to read and files to create/modify.

Before finalizing artifacts:
1) Run at least one clarification round with the user.
2) Present a draft ticket rewrite for review.
3) Incorporate feedback and then save final artifacts.
4) Request explicit user approval token: `CONTEXT_APPROVED`.

Do not hand off to solution design until `CONTEXT_APPROVED` is received.

## Required analysis workflow
1) Parse ticket intent
- Extract objective, actors, triggers, constraints, dependencies, and acceptance expectations.

2) Build repository context map
- Identify current architecture touchpoints, services, repositories, data models, UI entry points, and config keys affected by ticket intent.
- Mirror existing patterns; do not propose novel architecture.

3) Detect ambiguity and risk
- List missing details that block implementation.
- List assumptions that could cause regressions.
- Identify boundaries: what ticket should explicitly include and exclude.

4) Generate clarification questions
- Ask only high-impact questions.
- Each question must explain why the answer changes implementation behavior.
- If raw ticket detail is sparse, do not finalize on first pass; ask for missing critical details.

5) Produce expansion artifact
- Write a concise implementation context brief with:
  - Problem statement
  - In-scope behavior
  - Out-of-scope behavior
  - Affected components (with repo paths)
  - Data/permission/service implications
  - Acceptance criteria draft
  - Open questions/decisions needed
- Save the final artifact to `agent-rules/tickets/<TICKET_ID>/CONTEXT_EXPANSION.md`.
- Record the exact resolved raw source ticket path in the artifact.
- Record the active local agent-rules version in the artifact.

6) Produce refined ticket draft
- Create `agent-rules/tickets/<TICKET_ID>/TICKET_REWRITE.md` as a rewritten, implementation-ready ticket.
- Include:
  - Title
  - Problem / Why
  - User impact
  - In-scope
  - Out-of-scope
  - Functional requirements
  - Non-functional constraints
  - Acceptance criteria
  - Dependencies / risks
- Keep rewrite faithful to ticket intent and user clarifications; do not invent product scope.
- Keep rewrite concise: target one practical page, prioritize clarity over exhaustiveness.
- Record the active local agent-rules version in the artifact.

## Output format (required)
- Section 1: `Ticket Intent Summary`
- Section 2: `Current Repo Context`
- Section 3: `Proposed Scope Boundaries (Will / Won't)`
- Section 4: `Ambiguities and Clarifying Questions`
- Section 5: `Implementation Readiness Checklist`
- Section 6: `Ticket Rewrite Summary`

## Quality bar
- No vague wording like "handle appropriately" or "as needed".
- Every scope statement must be testable.
- Keep recommendations minimal and tied to existing repo conventions.

## Handoff rule
The ticket is ready for implementation only when:
- Scope boundaries are explicit.
- Critical open questions are answered or documented as constraints.
- Acceptance criteria are concrete and observable.
- `TICKET_REWRITE.md` is reviewed by user and aligned with intent.
- User explicitly confirms `CONTEXT_APPROVED`.

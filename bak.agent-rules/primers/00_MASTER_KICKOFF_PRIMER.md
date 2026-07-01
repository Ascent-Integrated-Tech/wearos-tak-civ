# Master Kickoff Primer

Use `agent-rules/AGENT_INDEX.md` for agent selection and global rules.
Active ticket: `WEAR-XYZ`.
Raw source ticket path: `agent-rules/tickets/WEAR-XYZ/WEAR-XYZ.md`.

Follow this sequence:
1) `agent-rules/TICKET_CONTEXT_EXPANSION_AGENTS.md`
2) `agent-rules/SOLUTION_DESIGN_AGENTS.md`
3) `agent-rules/IMPLEMENTATION_AGENTS.md`

Critical constraints:
- Read only this ticket's markdown/context files unless I explicitly allow other ticket references.
- Keep outputs strictly in the required sections defined by the active agent file.
- Do not skip phases unless I explicitly tell you to.
- Do not advance phases without explicit approval tokens:
  - `CONTEXT_APPROVED` to move from context expansion to solution design.
  - `APPROVED FOR IMPLEMENTATION` to move from solution design to implementation.
- Ticket artifacts:
  - Source input resolution: `agent-rules/tickets/WEAR-XYZ/TICKET.md`, then `agent-rules/tickets/WEAR-XYZ/WEAR-XYZ.md`, then bootstrap `agent-rules/tickets/WEAR-XYZ/TICKET.md` from Jira via `zsh -lic 'details WEAR-XYZ'` if neither local file exists, then explicit source path I provide.
  - Ticket rewrite output: `agent-rules/tickets/WEAR-XYZ/TICKET_REWRITE.md`
  - Expansion output: `agent-rules/tickets/WEAR-XYZ/CONTEXT_EXPANSION.md`
  - Design output: `agent-rules/tickets/WEAR-XYZ/SOLUTION_DESIGN.md`

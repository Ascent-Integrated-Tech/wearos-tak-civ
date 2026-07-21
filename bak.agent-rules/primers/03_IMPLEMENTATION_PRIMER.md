# Implementation Primer

Use `agent-rules/IMPLEMENTATION_AGENTS.md` and `agent-rules/AGENT_INDEX.md`.
Active ticket: `WEAR-XYZ`.
Raw source ticket path: `agent-rules/tickets/WEAR-XYZ/WEAR-XYZ.md`.

Instructions:
- Read only this ticket's markdown/context files unless I explicitly allow other ticket references.
- Resolve source input in this order: `agent-rules/tickets/WEAR-XYZ/TICKET.md`, then `agent-rules/tickets/WEAR-XYZ/WEAR-XYZ.md`, then bootstrap `agent-rules/tickets/WEAR-XYZ/TICKET.md` from Jira via `zsh -lic 'details WEAR-XYZ'` if neither local file exists, then explicit source path I provide.
- Use resolved source ticket plus `agent-rules/tickets/WEAR-XYZ/TICKET_REWRITE.md`, `agent-rules/tickets/WEAR-XYZ/CONTEXT_EXPANSION.md`, and `agent-rules/tickets/WEAR-XYZ/SOLUTION_DESIGN.md` when present.
- Verify `APPROVED FOR IMPLEMENTATION` exists in `agent-rules/tickets/WEAR-XYZ/SOLUTION_DESIGN.md`; if missing, stop and ask me.
- Implement only the approved design contract for this ticket.
- Follow branch, build, and commit rules exactly as defined in the implementation agent instructions.

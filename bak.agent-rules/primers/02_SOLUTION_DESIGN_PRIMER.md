# Solution Design Primer

Use `agent-rules/SOLUTION_DESIGN_AGENTS.md` and `agent-rules/AGENT_INDEX.md`.
Active ticket: `WEAR-XYZ`.
Raw source ticket path: `agent-rules/tickets/WEAR-XYZ/WEAR-XYZ.md`.

Instructions:
- Read only this ticket's markdown/context files unless I explicitly allow other ticket references.
- Resolve source input in this order: `agent-rules/tickets/WEAR-XYZ/TICKET.md`, then `agent-rules/tickets/WEAR-XYZ/WEAR-XYZ.md`, then bootstrap `agent-rules/tickets/WEAR-XYZ/TICKET.md` from Jira via `zsh -lic 'details WEAR-XYZ'` if neither local file exists, then explicit source path I provide.
- Use resolved source ticket plus `agent-rules/tickets/WEAR-XYZ/TICKET_REWRITE.md` and `agent-rules/tickets/WEAR-XYZ/CONTEXT_EXPANSION.md` plus current repo context.
- Drive back-and-forth questions to finalize explicit `Will Do` / `Won't Do` boundaries.
- Maintain a decision log and explicitly mark each decision as proposed/approved/deferred.
- Produce the required output sections from the solution design agent.
- Save final output to `agent-rules/tickets/WEAR-XYZ/SOLUTION_DESIGN.md`.
- Record the exact source ticket path used in the output.
- Do not hand off to implementation until I explicitly reply `APPROVED FOR IMPLEMENTATION`.
- Do not implement code yet.

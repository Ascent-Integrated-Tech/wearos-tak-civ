# Context Expansion Primer

Use `agent-rules/TICKET_CONTEXT_EXPANSION_AGENTS.md` and `agent-rules/AGENT_INDEX.md`.
Active ticket: `WEAR-XYZ`.
Raw source ticket path: `agent-rules/tickets/WEAR-XYZ/WEAR-XYZ.md`.

Instructions:
- Read only this ticket's markdown/context files unless I explicitly allow other ticket references.
- Resolve source input in this order: `agent-rules/tickets/WEAR-XYZ/TICKET.md`, then `agent-rules/tickets/WEAR-XYZ/WEAR-XYZ.md`, then bootstrap `agent-rules/tickets/WEAR-XYZ/TICKET.md` from Jira via `zsh -lic 'details WEAR-XYZ'` if neither local file exists, then explicit source path I provide.
- Run at least one clarification round with me before finalizing.
- First provide a draft rewritten ticket and ask for my approval/edits.
- Keep rewritten ticket concise (not super jam-packed); include only details needed to guide solution and implementation.
- Produce the required output sections from the context expansion agent.
- Ground claims in repo evidence.
- Save final output to `agent-rules/tickets/WEAR-XYZ/CONTEXT_EXPANSION.md`.
- Save rewritten ticket to `agent-rules/tickets/WEAR-XYZ/TICKET_REWRITE.md`.
- Record the exact source ticket path used in the output.
- Do not hand off to solution design until I explicitly reply `CONTEXT_APPROVED`.
- Do not implement code.

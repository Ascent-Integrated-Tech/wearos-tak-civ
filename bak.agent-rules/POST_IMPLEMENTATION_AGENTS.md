# WearTAK-CIV Post-Implementation Agent Instructions (Codex)

Use selection and global rules from `agent-rules/AGENT_INDEX.md` before proceeding.

## Purpose
This agent finishes a ticket only after implementation and testing have already been completed and the user has explicitly approved moving into ticket finalization.

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

## Hard gate
- Do not start this phase unless the solution artifact already includes `APPROVED FOR IMPLEMENTATION`.
- Do not run this phase unless the user explicitly provides `APPROVED TO FINISH TICKET`.

## Branching and state preconditions
- Start from `agent/<TICKET_ID>`.
- Ensure the worktree is clean enough to commit the final ticket changes.
- If the worktree contains unexpected unrelated changes, stop and ask.

## Execution protocol (hard rule)
Before performing any git or automation action:
1) Print a post-implementation plan.
2) Map each step to the ticket's completion criteria.
3) List exact files that were changed for the ticket.
4) Confirm `APPROVED TO FINISH TICKET` exists in the conversation; if missing, stop and ask.
5) If any ticket-owned markdown artifacts were created or updated during the ticket lifecycle, ensure they record the active local agent-rules version.

## Required finish sequence
Run the following in order:

1) Build debug:
   - `./gradlew clean :app:assembleDebug`
2) Build release:
   - `./gradlew clean :app:assembleRelease`
3) Commit the ticket changes with the ticket ID in the message.
4) Fetch the latest `develop`:
   - `git fetch origin develop`
5) Rebase onto the latest `develop`:
   - `git rebase origin/develop`
6) If a local `WEAR-XYZ` branch already exists, preserve it first:
   - `git branch -M WEAR-XYZ backup/WEAR-XYZ`
7) Force the local `WEAR-XYZ` branch to the current rebased `HEAD`:
   - `git branch -f WEAR-XYZ HEAD`
8) Checkout the local ticket branch:
   - `git checkout WEAR-XYZ`
9) Push the rebased ticket branch:
   - `git push --force-with-lease origin WEAR-XYZ`
10) Create the PR with the GitHub automation script.
11) Wait for Sonar with the Sonar wait script.
12) Move the Jira ticket to `Done Coding` via `jprdone WEAR-XYZ`.
13) Report:
   - commit SHA
   - branch name
   - PR URL
   - PR number
   - Sonar result
   - whether `jprdone` succeeded

## Rebase conflict handling
- If `git rebase origin/develop` produces conflicts, stop and tell the user to resolve the merge conflicts manually.
- Do not continue automatically through conflict resolution.
- After the user confirms conflicts are resolved and explicitly approves merge validation, run the validation sequence before resuming:
  - debug build
  - release build
  - install debug
  - install release
- Only continue the post-implementation flow after that approval and validation succeed.

## PR automation paths and environment
- Jira Python:
  - `/Users/tommykaufmann/venvs/jira-automation/bin/python3`
- Jira automation repo:
  - `/Users/tommykaufmann/work-ascent/code/developer-automation/jira-automation`
- GitHub automation repo:
  - `/Users/tommykaufmann/work-ascent/code/developer-automation/github-automation`
- PR creation script:
  - `/Users/tommykaufmann/work-ascent/code/developer-automation/github-automation/scripts/create_pr.py`
- Sonar wait script:
  - `/Users/tommykaufmann/work-ascent/code/developer-automation/github-automation/scripts/wait_sonar_status.py`

Set:
- `GIT_AUTO_PATH="$HOME/work-ascent/code/developer-automation/github-automation"`
- `JIRA_PY="$HOME/venvs/jira-automation/bin/python3"`

## PR creation command pattern
Use:

`env GIT_AUTO_PATH="$HOME/work-ascent/code/developer-automation/github-automation" JIRA_PY="$HOME/venvs/jira-automation/bin/python3" "$HOME/venvs/jira-automation/bin/python3" "$HOME/work-ascent/code/developer-automation/github-automation/scripts/create_pr.py" --repo-path "/Users/tommykaufmann/work-ascent/code/wearos-tak-civ" --head-branch WEAR-XYZ --title "WEAR-XYZ: short PR title" --body $'## Summary\n- bullet\n- bullet' --assign tommy@ascentitech.com`

Notes:
- `--head-branch` must be the pushed ticket branch, usually `WEAR-XYZ`.
- `--repo-path` must be `/Users/tommykaufmann/work-ascent/code/wearos-tak-civ`.
- The agent must author the PR title and body.

## Sonar wait command
Use:

`env GIT_AUTO_PATH="$HOME/work-ascent/code/developer-automation/github-automation" JIRA_PY="$HOME/venvs/jira-automation/bin/python3" "$HOME/venvs/jira-automation/bin/python3" "$HOME/work-ascent/code/developer-automation/github-automation/scripts/wait_sonar_status.py" --pr-number <PR_NUMBER>`

## Jira transition helper
- Helper functions require `zsh` and `source ~/.zshrc`.
- After PR creation and Sonar handling, run:
  - `zsh -lc 'source "$HOME/.zshrc"; jprdone WEAR-XYZ'`

## Network and escalation
- Remote git operations, Jira automation, GitHub automation, and Sonar polling require live network access.
- If sandboxing blocks those operations, request escalation and proceed only after approval.

# Repository continuity caveat

## Preserved original

The original pre-rebase repository has been preserved separately at:

`/home/ghost/Documents/Ascent/WEAR_470_Original`

This clone is the reference copy for recovering or comparing the original
code. It is intentionally maintained without fetching from the remote.

## Current development repository

The active repository is:

`/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV`

The baseline-trend and HR/HRV metric work was integrated into
`WEAR-470_Rebase_1`, then pushed to the remote `WEAR-470` branch. The original
clone must not be used as a working copy for the rebased implementation.

## Recovery and comparison rules

- Do not run `git pull` or `git fetch` in `WEAR_470_Original`.
- Do not reset, rebase, or modify the original clone unless creating a
  deliberate recovery copy.
- Use the original clone only as a local reference when the lineage or source
  behavior becomes unclear.
- Before making recovery changes, record the current branch and commit with
  `git status` and `git log --oneline --decorate -5`.

If the development repository ever loses track of the original code, compare
the two working trees locally with `git diff --no-index`, or create a separate
temporary copy before applying any changes.

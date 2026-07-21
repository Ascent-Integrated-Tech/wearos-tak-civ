# WEAR-537

- Source: Jira bootstrap via `details WEAR-537`
- Bootstrapped: 2026-06-17

## Summary
WearTAK and ATOS debug release failure

## Status
Design

## Assignee
tommy <tommy@ascentitech.com>

## Reporter
tommy <tommy@ascentitech.com>

## Created
2026-06-17T09:45:51.192-0500

## Updated
2026-06-17T09:45:57.502-0500

## Description
Regarding problems with IPC with release builds, here are the results that I've seen:

- ATOS Debug + WearTAK Debug = Works
- ATOS Debug + WearTAK Release = Doesn't Work
- ATOS Release + WearTAK Release = Doesn't Work
- ATOS Release + WearTAK Debug = Works

It seems in the release build of WearTAK, IPC no longer works with ATOS, or potentially any other plugin.

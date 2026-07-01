#!/usr/bin/env zsh
set -euo pipefail

REPO="/Users/tommykaufmann/work-ascent/code/wearos-tak-civ"
TICKET_ID="${1:-}"

cd "$REPO"

if [[ -z "$TICKET_ID" ]]; then
  read -r "TICKET_ID?Ticket ID (e.g., WEAR-385): "
fi

TICKET_DIR="$REPO/agent-rules/tickets/$TICKET_ID"
TICKET_FILE="$TICKET_DIR/TICKET.md"
LEGACY_TICKET_FILE="$TICKET_DIR/$TICKET_ID.md"
RESOLVED_TICKET_FILE="$TICKET_FILE"

if [[ ! -d "$TICKET_DIR" ]]; then
  mkdir -p "$TICKET_DIR"
fi

if [[ ! -f "$TICKET_FILE" && ! -f "$LEGACY_TICKET_FILE" ]]; then
  echo "Bootstrapping $TICKET_ID ticket context via: details $TICKET_ID"
  if ! zsh -lic "details $TICKET_ID" > "$TICKET_FILE"; then
    rm -f "$TICKET_FILE"
    echo "ERROR: Failed to fetch ticket details for $TICKET_ID" >&2
    exit 1
  fi
elif [[ -f "$LEGACY_TICKET_FILE" ]]; then
  RESOLVED_TICKET_FILE="$LEGACY_TICKET_FILE"
fi

BRANCH="agent/$TICKET_ID"
git checkout -B "$BRANCH"

echo "On branch $BRANCH"
echo "Start Codex and tell it to read:"
echo "  $RESOLVED_TICKET_FILE"
echo "  $REPO/agent-rules/WEARTAK_CONTEXT.md"

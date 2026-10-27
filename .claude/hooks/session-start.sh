#!/bin/bash
set -euo pipefail

# Only needed in Claude Code cloud sessions
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PLUGIN="superpowers@claude-plugins-official"

# Idempotent: skip if the plugin is already installed
if claude plugin list 2>&1 | grep -q "superpowers@claude-plugins-official"; then
  exit 0
fi

claude plugin marketplace add anthropics/claude-plugins-official || true
claude plugin install "$PLUGIN"

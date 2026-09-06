# ==============================================================================
# 📋 CONFIG — shared variables for all modules
# Usage in other modules: {{SERIAL}}, {{SKIP_ADB}}, {{DESKTOP_DB}}, etc.
# ==============================================================================

SERIAL   := env_var_or_default("ADB_SERIAL", "")
SKIP_ADB := env_var_or_default("SKIP_ADB", "0")

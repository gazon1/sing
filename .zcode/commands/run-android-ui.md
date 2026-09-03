# /run-android-ui — Full UI interaction loop on Android

Launches the app via MCP, then runs a full UI automation loop:
baseline screenshot → describe UI → resolve element → tap/type → after screenshot → check logs.

## Usage

```
/run-android-ui <description>
```

- `description`: Natural language description of the element to interact with (e.g., "notes tab", "new task button", "first task checkbox").

## Full MCP sequence

```
1. mcp__android_emulator__android_preflight
2. mcp__android_emulator__android_discover_project
3. mcp__android_emulator__android_build_and_run
4. mcp__android_emulator__android_screenshot        ← baseline
5. mcp__android_emulator__android_ui_status
6. mcp__android_emulator__android_ui_describe       ← full UI tree
7. mcp__android_emulator__android_ui_resolve         ← find element by contentDescription/text
8. mcp__android_emulator__android_ui_tap             ← tap the resolved element
9. mcp__android_emulator__android_screenshot         ← after state
10. mcp__android_emulator__android_logs              ← check for exceptions
```

## Interaction options

- `/run-android-ui "Tasks tab"` → tap the Tasks navigation item
- `/run-android-ui "New note button"` → tap FAB or button
- `/run-android-ui "search field"` → tap → type text with `android_ui_type_text`

## Notes

- Uses `contentDescription` as primary locator (plentiful in this app: "Toggle complete", "Delete", "Send", "Attachments (N)").
- Falls back to coordinates if `ui_resolve` can't find the element.
- If `android_ui_status` reports UI Automator unavailable, fall back to screenshot-only verification.

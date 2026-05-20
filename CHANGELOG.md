<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# IDE Introspect MCP Changelog

## [Unreleased]
### Added
- **Phase 1 — Tier 1 MCP tools** registered via `com.intellij.mcpServer.mcpToolset` extension point:
  - `ui.get_tree`, `ui.find_by_name`, `ui.find_by_coordinates`, `ui.find_by_xpath`, `ui.get_properties`
  - `screenshot.capture`, `screenshot.crop`
  - `arch.list_extension_points`, `arch.list_extensions_for_ep`, `arch.list_plugins`,
    `arch.get_plugin_details`, `arch.find_extenders_of`
- **Phase 1 — Platform Explorer tool window** (right anchor) with three view modes
  (By Plugin / By Extension Point / By Plugin Dependencies), SpeedSearch, live filter,
  HTML details panel, and copy-id context menu.
- **Phase 2 demo — `exec.execute_kotlin_in_ide`** (opt-in) backed by `kotlin-scripting-jsr223`,
  per-call confirmation dialog, textual safety blacklist, and audit log.
- Settings page under Settings → Tools → IDE Introspect MCP for the Phase 2 opt-in.

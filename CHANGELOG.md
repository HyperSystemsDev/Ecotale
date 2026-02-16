# Changelog

All notable changes to Ecotale will be documented in this file.

## [1.0.8] - 2026-02-08

### Fixed
- **MySQL connection timeout** - Connections no longer die after ~75 min of inactivity
  - Integrated HikariCP connection pooling with keepalive
  - Pool sized for 500+ concurrent users (25 max connections)

### Changed
- **Shadow plugin** - Downgraded to 8.3.8 for Java 25 ASM compatibility
- **HikariCP relocated** to `com.ecotale.lib.hikari` to avoid mod conflicts

---

## [1.0.7] - 2026-01-31

### Added
- **MongoDB support** - New database option for servers

### Improved
- **MySQL database** - Now supports all features (leaderboards, trends, transaction history)

### Fixed
- **Commands** - Fixed issues with economy commands not working correctly

## [1.0.6] - 2026-01-30

### Fixed
- **Player data now saves correctly on server shutdown** - Fixed an issue where player balances could be lost during server restarts
- **Player names show correctly in Admin Panel** - The Players and Top tabs now show actual usernames instead of UUIDs for offline players
- **MultipleHUD compatibility** - Finaly fixed compatibility with other HUD mods

### Added
- **Symbol Position Option** - New toggle in Config tab to display currency symbol on the left (`$ 100`) or right (`100 $`) - 
- **Toggle for HUD Translation** - New option in Config to use translated or custom HUD prefix text

---

## [1.0.5] - 2026-01-24

### Fixed
- **Console Rewards** - `/eco give/set/take` now works correctly for offline players via manual argument parsing
- **Permissions** - Fixed Admin GUI access denied error (node: `ecotale.ecotale.command.eco`)

### Added
- **Offline Name Resolution** - Integration with PlayerDB to show real usernames for offline players
- **Security Hardening** - Strict regex validation for usernames to prevent injection attacks

## [1.0.4] - 2026-01-20

### Fixed
- **Performance improvements** - Optimized player loading and memory management for large databases
- **HUD Stability** - Improved instance handling during world changes

### Added
- **Optimized Data Loading** - Implemented lazy loading and cache eviction policies

---

## [1.0.3] - 2026-01-20

### Fixed
- **HUD stability** - improved error handling
- **World change logic** - resolved issues when teleporting

### Changed
- **EnableHudAnimation** defaults to `false` for better mod compatibility

---

## [1.0.2] - 2026-01-19

### Fixed
- **MultipleHUD crash fix** - HUD updates now use incremental `update()` instead of full `show()`
  - Prevents crash when other mods' HUDs fail during MultipleHUD's build loop
  - Adds defensive exception handling to prevent crash propagation

---

## [1.0.1] - 2026-01-19

### Added
- **MultipleHUD compatibility** - Optional support for the [MultipleHUD](https://www.curseforge.com/hytale/mods/multiplehud) mod
  - Auto-detects MultipleHUD at runtime via reflection
  - If present, uses its API to allow multiple HUDs to coexist
  - Falls back gracefully to vanilla HUD if not installed

---

## [1.0.0] - 2026-01-18

### First Release

Complete economy system for Hytale servers.

### Features

#### Core Economy
- **Player balances** - Persistent storage with atomic operations
- **Transfers** - Player-to-player payments with configurable fees
- **Admin controls** - Give, take, set, reset balances

#### User Interface
- **Balance HUD** - On-screen display with smart formatting (K, M, B)
- **Admin Panel GUI** - 5 tabs: Dashboard, Players, Top, Log, Config
- **Pay GUI** - Player-to-player payment interface
- **Multi-language** - 6 languages supported

#### Storage
- **H2 Database** - Default, fastest, embedded
- **MySQL** - Shared database for multi-server
- **JSON** - Human-readable files for debugging

#### API
- `EcotaleAPI` - Public static methods for all economy operations
- `PhysicalCoinsProvider` - Interface for coin drop plugins
- Rate limiting and thread safety built-in
- Cancellable events for balance changes

### Commands
| Command | Permission |
|---------|-----------|
| `/balance` | None (all players) |
| `/pay` | None (all players) |
| `/eco` | `ecotale.ecotale.command.eco` |

### Languages
- English (en-US)
- Spanish (es-ES)
- Portuguese (pt-BR)
- French (fr-FR)
- German (de-DE)
- Russian (ru-RU)

### Technical
- Shadow JAR with all dependencies included
- Thread-safe with per-player locking
- Async database operations
- Optimized for 500+ concurrent players

# KiloProxy — Agent Rules

## Build (mandatory)
- Use ONLY the GitHub builder (`.github/workflows/build.yml`). Never build locally on this machine.
- After pushing code to `master`, check past successful build durations (`gh run list` — recent successful runs took ~4-5 min) and wait about that long in ONE `sleep`, then check the run status once. If still running, keep waiting in longer sleeps (~2-5 min, sized to the observed duration) — do NOT poll every 30 seconds.
- On failure: read the failing step, fix the code, commit, and push again.
- On success: proceed with download/install per below.

## Commit & Push
- Commit and push to `master` after fixes are done — no separate `push` command needed. Never leave completed fixes uncommitted or sitting unpushed.
- Identity: `Cryptoistaken` / `traderspopy@gmail.com`; push with `git -c credential.helper='!gh auth git-credential' push origin master`.

## Download & Install
- Do NOT download or deliver the APK by default — the user updates from inside the app. Only download the **universal release APK** (versioned by CI) from the `app-release` artifact if the user explicitly asks for it.
- If asked: fresh-download to a clean directory before installing (stale APKs caused version/signature mismatch before).
- Since the persistent release keystore (GitHub secrets `RELEASE_KEYSTORE_*`) was introduced, every build is signed with the SAME key and `versionCode` increases monotonically (CI `GITHUB_RUN_NUMBER` + 100). Updates are install-overs and PRESERVE all app data — never uninstall just to update.

### Install flow
1. Check if ADB device `localhost:5557` is alive (`adb devices` → shows `device`).
2. If alive:
   - Install over the old app WITHOUT uninstalling, so profiles/usage data are preserved:
     `adb -s localhost:5557 install -r <apk>`
   - Only uninstall first if a signature mismatch or downgrade is reported:
     - `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_FAILED_SIGNATURE` → signature differs (one-time migration from pre-keystore builds, or a different ABI build).
     - `INSTALL_FAILED_VERSION_DOWNGRADE` → installed versionCode is higher (e.g. a different ABI artifact); uninstall, or pull the matching ABI.
   - If not installed, install directly.
   - Verify with `adb -s localhost:5557 shell dumpsys package com.kiloproxy.app`.
3. If NOT alive: download the APK anyway, then STOP and wait for the user. Do NOT start any emulator/AVD on your own.
   - The user may skip the install, or start the emulator and tell you to install.
   - When the user later says to install after starting the device: install with `adb -s localhost:5557 install -r <apk>`; only uninstall first on signature/downgrade errors.

## Device notes
- App package: `com.kiloproxy.pro`. Device ABI: supports `arm64-v8a`.
- Cross-ABI versionCode mismatch causes `INSTALL_FAILED_VERSION_DOWNGRADE` — install the ABI that matches the device; only uninstall before switching ABIs.
- Signature mismatch → the installed app was signed with an older key (pre-keystore ephemeral CI key, or a different ABI build); uninstall once, then all future updates install over cleanly.

## State Snapshot & Restore

Before making any major changes (UI redesign, architecture changes, etc.),
always snapshot the current working state so you can restore it later.

### Creating a snapshot
```bash
# Tag the current commit with a descriptive name
git tag -a pre-ui-redesign -m "Working state before UI redesign"

# Push the tag to remote
git push origin pre-ui-redesign
```

### Listing available snapshots
```bash
# List all tags
git tag -l

# List tags with their commit dates
git tag -l --sort=-creatordate
```

### Restoring a snapshot
```bash
# Option 1: Reset hard to a tagged state (DESTRUCTIVE — discards all changes)
git checkout pre-ui-redesign
git checkout -b restore-from-pre-ui-redesign
# Now you're on a new branch at the old state

# Option 2: Create a branch from a tag (SAFE — preserves current work)
git checkout -b ui-redesign-attempt-1 pre-ui-redesign
# You now have a branch with the old state

# Option 3: Cherry-pick specific commits from a snapshot
git log pre-ui-redesign..HEAD --oneline  # see what changed since snapshot
git revert <commit-hash>                  # undo a specific commit
```

### Tag naming convention
- `pre-<feature-name>` — before starting a feature (e.g. `pre-ui-redesign`)
- `stable-<date>` — known working release (e.g. `stable-2026-08-07`)
- `post-<feature-name>` — after completing a feature (e.g. `post-ui-redesign`)

### Existing snapshots
| Tag | Commit | Date | Description |
|---|---|---|---|
| `pre-ui-redesign` | `397d4b0` | 2026-08-07 | Engine intact, CI passing, floating bubble fixed. Use this to restore before any UI redesign work. |
| `pre-proton-settings` | (pre-proton-settings commit) | 2026-08-12 | Working state before ProtonVPN-style settings redesign (UI only). |
| `pre-netshield` | (pushed) | 2026-08-12 | Before NetShield Phase 1 (pdnsd exclude-list DNS blocking). |
| `pre-proton-2-settings` | (pushed) | 2026-09-08 | Before replacing Split tunneling + Theme settings with the ProtonVPN mock design. |
| `pre-notif-and-dot-fixes` | (pushed) | 2026-09-09 | Before notification large-icon fix + effective-theme wiring for bubble/popup. |
| `pre-accelerator` | `353da5e` | 2026-09-10 | Before VPN Accelerator engine work (UI toggle only, engine untouched). |

> **One-time (do before the notification/dot pass):** done 2026-09-09 — tag `pre-notif-and-dot-fixes` created and pushed, table updated.

### Quick restore (pre-ui-redesign)
```bash
# Safe restore — creates a new branch from the snapshot
git checkout -b ui-redesign pre-ui-redesign

# If you need to go back to THIS commit directly (destructive)
git reset --hard 397d4b0
```

### Important notes
- Tags are lightweight and don't affect branch history.
- Always push tags to remote (`git push origin <tag>`) so they survive local disasters.
- The `DesignPlan.md` file in the repo root describes the UI redesign plan.
- Engine code (`SocksVpnService.kt`, `IVpnService.aidl`, `Utility.kt`, `ProfileManager.kt`) must never be modified by UI changes.

## User-Facing Messages

All user-facing text (Toast, Snackbar, notification content, status labels, error messages) must be **plain ASCII text only**. No emojis, no icons, no decorative unicode symbols.

**Allowed:** letters, digits, spaces, basic punctuation (`. , ! ? : ; - ( ) / ' "`).
**Forbidden:** `✓ ✗ ⚠ ⏳ 🔗 🌐 🇩🇪 … → — · ｢｣` and any other non-ASCII character in user-visible strings.

Bad: `"✓ Proxy works"`, `"Checking for updates…"`, `"Connected to ｢%s｣"`
Good: `"Proxy works"`, `"Checking for updates"`, `"Connected to %s"`

Keep messages short and direct. State what happened, nothing else.

## Filesystem Map & References (KEEP UPDATED)

> **Rule:** Whenever the repo structure changes (files/dirs added, moved, renamed, or deleted), update this map in the same commit. Read this section first for fast orientation instead of re-scanning the tree.

### Root
| Path | Purpose |
|---|---|
| `AGENTS.md` | This file — agent rules, build/install flow, snapshots, filesystem map |
| `task.md` | VPN Accelerator task (experimental connect-time goal, SOCKS5-client scope) |
| `checker/` | Own exit-IP checker (Cloudflare Worker source; deploys via wrangler, outside the APK build) |
| `protonvpn-settings.html` | Settings mock reference (tracked; `design/` docs were deleted) |
| `build.gradle` | Root Gradle build (plugins: android.application, Kotlin compose) |
| `settings.gradle` / `gradle.properties` / `gradle/wrapper/gradle-wrapper.properties` | Gradle config (Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, Java 17) |
| `.github/workflows/build.yml` | **ONLY** build entry point (CI GitHub Actions; never build locally) |
| `.keystore-backup/` | Local keystore backup — signing handled via GitHub secrets in CI |
| `.gitignore` | Ignorable paths |

### `app/build.gradle` (app module)
- compileSdk 36, minSdk 21, **targetSdk 36**
- Monotonic `versionCode`: CI `GITHUB_RUN_NUMBER + 100`, local `git commit count + 100`
- Per-ABI versionCode override: `abi_rank * 67 + base` (arm7=1, arm64=2, x86=3, x86_64=4)
- ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (+ universal) via `-Pabi=` split
- Native: ndkBuild via `src/main/jni/Android.mk`, NDK 27.0.12077973, `useLegacyPackaging = true`
- Signing: persistent release key from CI env `KILO_KEYSTORE_*`, else debug
- R8 minify+shrink on release; Java 17; Compose BOM `2024.10.01`, material3, navigation-compose 2.8.3, lifecycle 2.8.6, activity 1.9.x, appcompat 1.6.1, material 1.11.0, security-crypto 1.1.0-alpha06
- `tasks.configureEach` copies pdnsd/tun2socks `.so` from `build/intermediates/cxx` → `src/main/jniLibs`

### Kotlin source — `app/src/main/java/net/typeblog/socks/`
| File | Responsibility |
|---|---|
| `MainActivity.kt` | Compose host activity, entry point, launcher |
| `SocksApplication.kt` | Application class (init, context wiring) |
| `SocksVpnService.kt` | **Engine** — VpnService + tun2socks/pdnsd spawn, tunnelling, notifications, stats, IP check. NEVER modify for UI. |
| `FloatingControlService.kt` | Floating bubble (60dp) + flag pill overlays, long-press popup; WindowManager, SYSTEM_ALERT_WINDOW |
| `BubbleMenuOverlay.kt` | Popup overlay shown near bubble: country list, search, positioning; window params/IME handling |
| `BootReceiver.kt` | BOOT_COMPLETED auto-start receiver |
| `AppSelector.kt` | Per-app selection list adapter |
| `System.kt` | JNI bridge (sendfd) |

Notes on the merged notification/dot pass:
- `SocksVpnService.kt` — reuses the shared "floating control" notification (id 2, channel `floating_control`) instead of a separate VPN notification; user sees only ONE notification. `stopMe` uses DETACH (not REMOVE) so the shared FGS notification is not torn down.
- `FloatingControlService.kt` — notification uses custom RemoteViews: always-visible centered pill with Connect/Disconnect; connected bubble color is now `#DC2626` (light-theme `LightError`) instead of `DarkError #EF4444`.
- `BubbleMenuOverlay.kt` / `bubble_country_row.xml` — connected-dot now positioned where the dial code was shown.

### `.../util/`
| File | Responsibility |
|---|---|
| `Constants.kt` | Intent extras, preference keys, actions |
| `Countries.kt` | Country list for bubble menu |
| `LogCollector.kt` | In-app log capture (Debug Logs screen) |
| `Profile.kt` / `ProfileFactory.kt` | Profile data class + factory (pre-defined server profiles) |
| `ProfileManager.kt` | **ENGINE** — profile CRUD, prefs. NEVER modify for UI |
| `ProxyProviders.kt` | Proxy provider catalog (proxy list presets) |
| `Routes.kt` | VpnService route selection (route config) |
| `SocksTester.kt` | SOCKS5 liveness/health probe |
| `ThemeMode.kt` | Effective theme (manual theme_mode override, else device) + themedContext for -night inflation |
| `Utility.kt` | **ENGINE** — pdnsd conf, ip lookups, misc helpers. NEVER modify for UI |

### `.../ui/`
- `components/` — Compose components: ConnectionCard, ProxyCard, ProfileDetailSheet, ProtonControls (ProtonSwitch + ProtonRadio + ProtonDialogRadioRow, mock-exact mono controls), SearchInput, SettingsItem, UpdateDialog
  - `ConnectionCard.kt` — Connect/Disconnect button is now text-only (icon removed); spinner shown while connecting.
  - `ProxyCard.kt` — Minimal card (profilecard.html v2): square, borderless (transparent 1dp keeps picked outline + layout), lifted `surfaceContainer` tile on `surface` page (`--tile` in mock), flag-emoji / server-glyph icon slot, name + app-green dot (hidden offline), host without port, Used total only. Whole card taps to `onSelect` (detail sheet, or pick in pickMode). No chips, no buttons. Long-press (`onLongPress`) enters multi-select and picks the card; picked cards (`checked`) use the overlay style (primary border + primaryContainer tint, no checkbox). Swipe right opens Edit, swipe left deletes immediately with a 5s Undo snackbar (no confirm); swipe disabled in pickMode/multi-select.
  - `ProfileDetailSheet.kt` — Bottom sheet opened by tapping a card: icon + name + sub, Provider/Type + Used/Server(no port) facts, Copy (clipboard `host:port:user:pass`, flips to green Copied, no Toast) / Test (SocksTester + Toast) / Edit / Duplicate (`duplicateProfile` in ProxiesScreen, `Profile.copyTo`, no engine change) / Delete rows with `ic_sheet_*` icons (`lucide_copy` for Copy). Delete reuses the existing confirm dialog. Opens fully expanded (`skipPartiallyExpanded`).
- `navigation/AppNavigation.kt` — NavHost destinations (incl. `theme` route)
- `screens/` — BubbleSettingsScreen, CountriesScreen, DebugLogsScreen, ProxiesScreen, SettingsScreen, SplitTunnelingScreen, StatusScreen, ThemeScreen, AdvancedSettingsScreen
  - `AdvancedSettingsScreen.kt` — Advanced Settings page (Accelerator master + Primary checker + Checker mode + Cache last IP + Proxy health probe + Recheck interval + Cache proxy DNS). Engine honors prefs only while master is ON.
  - `ThemeScreen.kt` — Theme picker page: Light / Dark / Device theme cards with mini phone previews; writes PREF_THEME_MODE.
  - `SplitTunnelingScreen.kt` — ProtonVPN mock design: feature header + toggle card, Mode row (dialog: Exclude/Include) + Apps row; apps page has search bar, selected-apps section (minus) and all-other-apps section (plus). Same engine prefs (PREF_ADV_PER_APP / PREF_ADV_APP_BYPASS / PREF_ADV_APP_LIST). IP-address rows skipped: engine has no IP split-tunneling support. Apps page opens directly via `startOnApps` arg (refuse-to-connect link).
  - `SettingsScreen.kt` — Features rows: "Split tunneling" (On/Off), "Theme" (subtitle = theme label), "Floating Bubble" (On/Off), "Advanced Settings" (On/Off, opens AdvancedSettingsScreen); no chevrons.
- `theme/` — Color, Fonts, Theme, Type (Compose theming, Geist fonts)
- `viewmodel/VpnViewModel.kt` — Vpn state, AIDL binding, split Include-empty guard, accelerator DNS warm-up

### Drawables added for this pass
- `drawable/lucide_minus.xml`, `ic_proton_filter.xml`, `ic_proton_apps.xml` (vector icons for the split tunneling rows)
- `drawable/ic_sheet_test.xml`, `ic_sheet_edit.xml`, `ic_sheet_duplicate.xml`, `ic_sheet_delete.xml` (filled icons for the profile detail sheet rows)
- `drawable/ic_notification_transparent.xml` (required invisible notification small icon)
- `drawable/ic_copy.xml`, `ic_paste.xml` (fill icons for Copy/Paste, tinted to text color; no green)

### Native C — `app/src/main/jni/`
| Area | Purpose |
|---|---|
| `Android.mk`, `Application.mk` | ndkBuild top-level build files |
| `badvpn/` | tun2socks engine (full badvpn fork: tun2socks/, lwip/ stack, client/, system/, etc.) |
| `hev/` | hev-socks5-tunnel 2.17.1 (MIT, experimental, behind `PREF_HEV_TUNNEL`): modern tun2socks, builds `libhev-socks5-tunnel.so`, JNI `hev.htproxy.TProxyService` |
| `pdnsd/` | pdnsd DNS proxy source |
| `libancillary/` | ancillary fd passing (sendfd recvfd) |
| `system.cpp` | JNI — `sendfd()` used by VPN tunnel setup |

### AIDL
- `app/src/main/aidl/net/typeblog/socks/IVpnService.aidl` — **ENGINE** binder interface between activity/UI and SocksVpnService (DO NOT touch for UI)

### Manifest — `app/src/main/AndroidManifest.xml`
- Permissions: INTERNET, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, QUERY_ALL_PACKAGES (split-tunnel), SYSTEM_ALERT_WINDOW, VIBRATE
- `SocksVpnService`: `process=":vpn"`, `exported=true`, BIND_VPN_SERVICE, fgType specialUse + subType property
- `FloatingControlService`: specialUse FGS
- `BootReceiver`: exported=false, BOOT_COMPLETED
- `FileProvider` authorities `${applicationId}.provider`, paths `@xml/file_paths`
- `networkSecurityConfig="@xml/network_security_config"`

### Resources — `app/src/main/res/`
- `assets/` — (empty; NetShield blocklists were removed — NetShield is now cloud-only)
- `layout/` — `app_item.xml`, `bubble_menu.xml` (bubble popup panel), `bubble_country_row.xml`, `notification_action.xml` (RemoteViews layout for the notification Connect/Disconnect pill)
- `drawable/` — lucide_* icons, menu_panel_bg, search_input_bg, signal_dot, logo_*, launcher, notification_pill, notification icons (pill button background)
- `font/` — Geist family TTFs (bold/medium/mono/pixel etc.)
- `mipmap-*/` — legacy + adaptive launcher icons
- `values/` — strings.xml, arrays.xml, styles.xml, pdnsd.xml, ruroute.xml, simpleroute.xml, ic_launcher_background
- `xml/` — network_security_config.xml (cleartext/trust config), file_paths.xml (FileProvider), settings.xml (preference screen XML)

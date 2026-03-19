# Changelog

## 1.2.0

### Added

- **Lifecycle-based session tracking.** New `SessionService` uses `ProcessLifecycleOwner` to count sessions based on foreground/background transitions (>30s threshold). Each new session generates a fresh `sessionId`, replacing the old 24h expiry. Push payload now includes `device.sessions`, `device.views`, and `device.firstSeenAt`.

### Fixed

- **Banner text color ignoring content-level `color` property.** Text and heading elements in banner designs now correctly read the Unlayer `color` field, with fallback to `textColor` then body default. Previously only `textColor` was checked, causing content with explicit `color` (e.g. white text on dark background) to render in the body default color (black).

## 1.1.2

### Bug Fixes

- **NPS follow-up keyboard UX**: Restored multi-line comment input (3-4 lines) and added auto-scroll so the submit button remains visible above the keyboard. The `ScrollView` now scrolls to the submit button when the input gains focus, and `SOFT_INPUT_ADJUST_RESIZE` resizes the bottom sheet. Tapping outside the input or pressing the submit button dismisses the keyboard.

## 1.1.1

### Bug Fixes

- **NPS not showing when stories are active**: NPS survey events emitted while `StoryViewerActivity` was on top of `MainActivity` were lost because the `SharedFlow` collector was paused (`repeatOnLifecycle(STARTED)`). Changed `NpsDisplayController` to use `replay = 1` with explicit cache clearing after consumption, so the NPS event is preserved and replayed when the activity resumes.
- **NPS follow-up keyboard UX**: The comment input field now uses single-line input with `IME_ACTION_DONE`, so the keyboard shows a checkmark/done button instead of Enter. Tapping outside the input or pressing the done key dismisses the keyboard. The submit button also dismisses the keyboard when tapped. The dialog window uses `SOFT_INPUT_ADJUST_RESIZE` to keep content accessible when the keyboard is open.
- **Improve support for App Inbox routing**: App inbox routing now follows a simplified convention.

## 1.1.0

### New Features

- **NPS Surveys**: Full NPS survey support with trigger evaluation, customEvent/cancelOnEvents client-side logic, submission API with retry, and built-in bottom sheet/modal UI (`NpsDialogFragment`).
- **Stories**: Instagram/Facebook-style story viewer with progress bars, tap navigation (left half = previous, right half = next), auto-advance, end behaviors (dismiss/loop/stayOnLast), and DesignRenderer-based slide rendering (`StoryViewerActivity`). Trigger types: immediately, delaySeconds, scrollPercentage, cartChanged, wishlistChanged. Interactive elements (buttons, links) inside slides receive taps correctly.
- **App Inbox**: Full inbox service with cursor-based pagination, optimistic updates with rollback, stale cache refresh (5 min), lifecycle-aware refresh on app resume, silent push sync (`inbox_sync` flag), and message lookup by ID. API methods: fetch messages, unread count, mark read, mark all read, delete, track action.
- **Carousel**: `DesignRenderer` now supports carousel content blocks with swipe navigation, left/right tap navigation, directional slide animations, autoplay with optional loop, and dot indicators or image preview strip.
- **Endpoint Override**: `setEndpointOverride(url)` on `RelevaClient` for local development with ngrok or custom endpoints.
- **RelevaConfig.enableInbox**: New config flag (default true) to enable/disable inbox feature.

### Breaking Changes

- **`BannerDisplayManager`**: `onLinkTap` is now a required constructor parameter (previously optional). Apps must provide a callback for handling link/button taps from banner content.
- **`StoryDisplayManager`**: `setOnLinkTap()` must be called before `attach()` (throws `IllegalArgumentException` otherwise).

### Changes

- `RelevaResponse` now includes `stories`, `nps`, and related helper methods (`hasStories`, `getStoriesByTag`, `getStoryByToken`).
- `RelevaClient` implements `InboxApiClient` interface and includes NPS (`trackEvent`, `submitNpsResponse`, `dispose`) and story tracking (`storyImpression`, `storyAction`) methods.
- `RelevaFirebaseMessagingService` handles silent push messages with `inbox_sync` flag — refreshes inbox and still displays the notification.
- `NotificationTrampolineActivity` handles `target=inbox` notifications by mapping to screen navigation.
- `InboxService` mutation methods (`markAsRead`, `markAllAsRead`, `deleteMessage`, `trackAction`) run on the service's own coroutine scope to survive fragment lifecycle cancellation.
- `InboxService` lifecycle observer registers on the main thread.
- Added `lifecycle-process` and `material` dependencies.

## 1.0.6

- Add banners support

## 1.0.5

### Bug Fixes

- Fix notification click tracking not firing. All notification taps now route through `NotificationTrampolineActivity` which tracks the callback URL immediately before navigating. Previously, non-URL notifications went directly to the main activity via `NavigationService.createAppIntent()`, which had two issues: (1) `ACTION_MAIN` + `CATEGORY_LAUNCHER` flags caused Android to skip `onNewIntent()` on warm start with `singleTask` activities, and (2) a Kotlin variable-shadowing issue inside the `Intent.apply` block could cause extras (including `callbackUrl`) to not be set.

## 1.0.4

- Add `skipMergeWithPreviousProfileId` option when setting profile ID.

## 1.0.3

- Always send `page.query` in tracking events.

## 1.0.2

- Initial public release.

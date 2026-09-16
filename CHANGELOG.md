# Changelog

## 1.4.1

Not yet released. 1.4.0 is tagged and published, so this lands under its own version
rather than in the section above.

### Fixed

- **A request that never got an answer was logged as nothing at all.** `RelevaClient`'s
  request log runs entirely after `newCall(...).execute()` returns, so a transport failure —
  no network, DNS failure, connect or read timeout, TLS failure — propagated to the caller
  without a line of its own, leaving an integrator debugging a flaky network with silence from
  the SDK. Observed during the Android device pass: a cold start in airplane mode produced zero
  `RelevaClient` lines, against four with the network up. The failure is now logged in the same
  `VERB path -> … in Nms` shape, with the exception's class and message, under the same
  `enableRequestLogging` gate, and rethrown unchanged — the request body is still never logged.
- **A story closed itself when the device was rotated.** The viewer's launch data was passed
  through a static map and consumed by the first `onCreate`, and the activity declares no
  `android:configChanges` — so a configuration change destroyed it, recreated it from the same
  intent, found nothing under the same key and finished. Rotation is the easiest trigger; a
  dark-mode toggle, a font- or display-size change, a locale change and a multi-window resize
  all take the same path. The data now lives as long as the launch does and is dropped when the
  viewer finishes, and the recreated viewer restores its slide, its remaining slide time and the
  fact that it has already tracked the story — so a rotation neither restarts the story nor
  counts a second impression. An intent with no launch key, and a story with no slides, still
  close immediately.
- **An NPS survey was destroyed when the device was rotated, losing a part-answered response.**
  The survey config and the callbacks were set as fields on the fragment by
  `NpsDialogFragment.newInstance`, and the FragmentManager recreates a fragment through its
  no-arg constructor — so a configuration change left the successor with a null config and it
  dismissed itself, while `NpsManagerService` had already marked the survey as shown for the
  rest of the session. Rotation is the easiest trigger; a dark-mode toggle, a font- or
  display-size change, a locale change and a multi-window resize all take the same path.
  Device-verified on a Xiaomi 2407FPN8EG (Android 16): the survey vanished on rotation and
  turning the phone back did not bring it back. The config now travels in the fragment's
  `arguments`, which the FragmentManager restores, and the step the user had reached — the
  selected score, the typed comment, whether the response was already sent — is saved in
  `onSaveInstanceState`; callbacks cannot go in a Bundle, so the fragment reads them from
  `NpsDisplayManager` and a recreated dialog submits to the same one as before. A fragment with
  no config at all still dismisses, `NpsDisplayManager` no longer stacks a second sheet on a
  survey already on screen, and a configuration change landing while a submission is suspended
  inside the callback no longer crashes on the way to the thank-you step.
  `NpsDialogFragment.newInstance` now takes just the config; the three-argument overload is
  kept, `@Deprecated`, and behaves exactly as it did before — its survey and its callbacks stay
  on the one instance it returns, it neither reads nor writes the ones `NpsDisplayManager`
  holds, and its dialog is still lost on a configuration change. Callers get the fix by moving
  to `NpsDisplayManager`, which is the integration the guide documents.
- **A momentary network failure or a transient 5xx dropped the event for good.**
  `RelevaClient.execute` made exactly one call and handed whatever came back — or whatever it
  threw — straight to the caller, so a pageview, cart sync, impression or push-token
  registration that met a blip was lost, while the Swift SDK against the same API retried and
  recovered. A failure that says nothing about the request itself — it never reached the server,
  or the server answered 5xx — is now retried on the Swift SDK's schedule: 1s after a transport
  failure, 2s after a 5xx, up to `RelevaConfig.maxRetryAttempts` retries on top of the first try
  (default 3, so 4 requests before giving up; 0 means a single attempt, no retries). The counting
  matches `NetworkService.executeRequest`'s `attemptsLeft` counter on the Swift side exactly, but
  the request count at that shared default only matches Swift's for `sendPushRequest` and
  `registerPushToken` — Swift hardcodes a smaller budget at every other retryable call site (NPS
  and inbox at 1 retry, banner/push-event at 2, `inboxTrackAction` at none), so this SDK now
  retries those endpoints more than Swift does; see the PR's Out of scope note for the
  per-endpoint gap. A 4xx and any 2xx are still returned on the first attempt, each retry is
  logged under the existing
  `enableRequestLogging` gate, and once the retries are spent the last failure reaches the
  caller exactly as it did before. Retrying carries the duplicate-POST risk the Swift SDK has
  shipped with: a request the server processed but whose answer was lost in transit is sent
  again — but only for a failure *before* a response arrives; a response that did arrive, whose
  body read then failed partway (a read timeout mid-transfer, the connection dropping after the
  headers), is handed to the caller after exactly one request, the same as before this change,
  since the server has already committed to that status. `submitNpsResponse` loses its own
  separate one-shot retry as part of this — it now gets exactly the same policy as every other
  request instead of a second, stacked retry layer, so a 4xx on that endpoint is no longer
  re-sent and a 5xx now gets this SDK's shared four-request budget instead of its old two — still
  more than Swift's own NPS budget of two, per the note above.

- **A profile merge was lost permanently if the app died before the first successful push.**
  `RelevaClient` kept the ids queued by `setProfileId` in an in-memory list only, while the new
  profile id itself was written to storage immediately. On the next launch the list was empty and
  the previous id was no longer recoverable — `storage.getProfileId()` already returned the new
  one — so the two identities were never linked, silently and permanently. Device-verified with a
  control: online, the push body carried `mergeProfileIds = ["ctl-A-000036"]`; with the radios off
  and a force-stop before any successful push, the next launch sent `mergeProfileIds = []`.

  The queue now lives in `StorageService` and nowhere else, so it survives the process by
  construction. Alongside that: a successful push removes only the ids it actually sent, so an id
  queued while that request was in flight is no longer dropped with them; `registerPushToken` no
  longer clears the queue, since its request never carried `mergeProfileIds` and clearing there
  only discarded a merge a later push still had to send; `skipMergeWithPreviousProfileId` (the
  logout path) clears the queue whether or not the id passed with it has changed, because a
  queued id is delivered against `profile.id` as it stands *at push time* — so keeping one past
  a logout would not preserve the old link (its other half is already gone) but would merge the
  signed-out user into the anonymous session; and, matching the Swift SDK, an id already queued is not queued
  again, so A → B → A → B queues `["A", "B"]` rather than `["A", "B", "A"]`.

  The wire format is unchanged. `profileChanged` is now sent as `true` whenever the body carries
  merge ids, which is the only combination of the two the backend has ever received — before the
  queue was durable, an id could not outlive the flag.

## 1.4.0

Everything here came out of a device pass against a real domain, replicating the
iOS/Swift QA on Android. Each fix was reproduced on a device before and after,
except where an entry below says otherwise.

### Fixed

- **Banners and stories were dropped silently past ten in flight.** `BannerDisplayController`
  used a `MutableSharedFlow` with `extraBufferCapacity = 10` and discarded `tryEmit`'s result,
  while the producer emits every `immediately` banner in one synchronous pass and the consumer
  renders one at a time. Measured: 19 emitted, 12 rendered, 7 gone with no log and no error.
  `StoryDisplayController` had the same shape. Both now use a much larger buffer (128 for
  banners, 64 for stories) with `DROP_LATEST` — both producers emit in priority/server
  order, so dropping the oldest would discard the highest-priority item first — and a
  dropped item is logged, as is an emission with no attached collector, which no buffer
  size can fix.
- **Stories stacked instead of queueing.** Every story emitted called `startActivity`, so a page
  with several opened several viewers at once (six live `StoryViewerActivity` instances were
  observed). They now queue and show one at a time.
- **Every story after the first was silently dropped.** The viewer covers its host, and the
  collector ran at `STARTED`, so it was cancelled the moment the first story opened; `storyFlow`
  has no replay, so the rest of the same pass was gone before the queue could hold it.
- **A disabled animation decided how long a story slide lasted.** The progress bar's
  `ValueAnimator` was also the slide timer, and its end listener advanced the slide — so with
  animations off (accessibility, battery saving, developer options) the system scaled the
  duration to zero and the whole story played in one frame. The advance is now a posted callback
  on a real clock; the animator only paints.
- **A story slide's call to action could not be tapped.** The action button was a child of the
  content layer, underneath the slide-navigation overlay, so the only way a tap could reach it was
  the overlay's own hit test — and on a device that test missed it: a tap well inside the button's
  bounds was handled as slide navigation and the button did nothing. Why it missed was not
  established, so rather than repairing the hit test the button now sits above the overlay and is
  reached by ordinary touch dispatch, the same way the close button already was. Taps that miss the
  button still fall through to navigation. Device retest pending: the "before" here is the QA
  observation above, the "after" still needs a device pass on the retap.
- **Banner popup content rendered behind the status bar.** The content is now inset by the real
  window insets, including the display cutout, while the background still runs edge to edge.
- **Design padding was applied in raw pixels rather than dp.** `parseEdgeInsets` left the density
  multiply to each caller and three of four callers omitted it, so every design rendered at a
  third of its intended padding on a 3x screen. The multiply now happens once, in the parser.
- **Overlapping inbox refreshes each fetched their own copy.** A cold open asks for a refresh
  from more than one place; with no in-flight guard that meant six requests where two would do.
  Callers now join a refresh already running.
- **A silent push was drawn as "You have a new notification".** `RelevaFirebaseMessagingService`
  displayed every message it received, inventing a title and body from `getDefaultNotificationTitle()`
  and a hardcoded string when the payload carried neither — so an `inbox_sync` push, whose whole
  point is to refresh the inbox in the background, also posted a visible IMPORTANCE_HIGH
  notification with no content of its own. A message with no notification payload and no `title`,
  `body` or `message` data key is now handled and not drawn. A message carrying either half still
  displays, with the existing default filling the other half. The unused `silent_channel` — created
  on every notification and never routed to, since `getNotificationChannelId()` returns the default
  unconditionally — is gone with it.

### Added

- **`Cart` totals helpers**, matching the Swift SDK: `itemCount`, `totalQuantity`, `totalPrice`,
  `isEmpty`, `contains(productId)`, `product(productId)`, and `CartProduct.totalPrice` /
  `hasPrice`. A missing price contributes 0 and a missing quantity counts as one, as on iOS.
- **Request logging for every call.** Logging had been a rule each verb followed, and the inbox —
  added later, calling the HTTP client directly — did not follow it: its two GETs and its DELETE
  were the only requests the SDK made that logged nothing. Every request now goes through one
  place that logs verb, endpoint, status and timing. Success is any 2xx, which also stops token
  registration (202) and delete (204) being logged as warnings for succeeding. A response body is
  only logged for a 5xx, capped at 500 chars — a 4xx gets status and timing only, since several of
  the API's validation errors echo the offending field value. New `RelevaConfig.enableRequestLogging`
  (default on) lets an integrator silence all of it in their own release builds.
- **NPS diagnostics.** `NpsManagerService` logged only its successes, so every way a survey can
  fail to appear was the same silence. It now names the config it received and the triggers it is
  waiting on, and logs an event that matched no trigger alongside the names it was compared
  against.
- **`InboxState.lastRefreshError`.** Set when a refresh fails and cleared on the next one that
  succeeds, so a host screen can show that a refresh failed instead of silently keeping stale
  data with nothing indicating anything went wrong.


## 1.3.0

### Added

- **Public `push(PushRequest)` API.** `RelevaClient.push(request)` is now public, so apps can compose a `PushRequest` via the fluent builder (`url`, `screenToken`, `productView`, `pageProductIds`, `pageCategories`, `pageQuery`, `pageFilter`, `locale`, `currency`, `cart`, `customEvents`) and send it directly. This is now the recommended pattern for screen views, product views, search, and checkout tracking.
- **`trackCustomEvent(event, pageUrl?, screenToken?)`** convenience on `RelevaClient` for tracking a single `CustomEvent` without building a `PushRequest` by hand.

### Documentation

- README and INTEGRATION_GUIDE rewritten around the builder pattern. The existing `trackScreenView` / `trackProductView` / `trackSearchView` / `trackCheckoutSuccess` / `trackScreenViewWithEvents` methods remain available for backward compatibility but are no longer the recommended entry point.

## 1.2.2

### Fixed

* **Session timeout fix** We consider a session has ended after 30 mintues of inactivity (industry standard as of 2026)


## 1.2.1
**Repackage**

## 1.2.0

### Added

- **Lifecycle-based session tracking.** New `SessionService` uses `ProcessLifecycleOwner` to count sessions based on foreground/background transitions (>30s threshold). Each new session generates a fresh `sessionId`, replacing the old 24h expiry. Push payload now includes `device.sessions`, `device.views`, and `device.firstSeenAt`.
- **Background image support for banners.** Body-level and row-level background images from Unlayer design JSON are now rendered natively. Supports size (cover/contain/custom). Flyout banners show the image behind the entire panel including the close button area.
- **Popups always full-screen.** Popup banners always fill the entire screen. Only the close button dismisses the popup (tapping outside no longer closes it). Background image fills the viewport. Close button positioned below the status bar.
- **Bar banner improvements.** Close button repositioned to sit at the content edge with ~1/4 overlap on both top and bottom bars. Bar layout background is now transparent instead of white, so the banner design's own background shows through without a padded frame.

### Fixed

- **Banner text color ignoring content-level `color` property.** Text and heading elements in banner designs now correctly read the Unlayer `color` field, with fallback to `textColor` then body default. Previously only `textColor` was checked, causing content with explicit `color` (e.g. white text on dark background) to render in the body default color (black).
- **Views counter off-by-one.** First push now correctly sends `views: 1` instead of `views: 0`.

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

# Changelog

## 1.5.3

PATCH: the changes below change behaviour but add and deprecate no public API. Verified with
`BannerChromeTest.kt` (see that file for coverage) AND on a physical device — Android 16,
example-kotlin section Q, 17 rows measured and photographed. The two chrome-layout fixes came
out of reading those photographs: neither is visible to a Robolectric assertion, because both
are about what a card PAINTS rather than where its `LayoutParams` put it.

### Fixed

- **The close button no longer covers the start of a bar's copy.** It is an absolute sibling
  drawn over the content, pulled out by a quarter of itself so it straddles the card's edge —
  which left it covering the band from 10dp to 34dp in from the right while the content was
  inset only 16dp, so the tail of a headline was drawn beneath the glyph. The right gutter is
  now 42dp (34 plus clearance) and is applied with `setPaddingRelative`, so it lands on the end
  side under an RTL layout direction where the button is. **This widens the right gutter on
  every bar**, authored chrome or not: the collision predates these keys, and a narrower card
  will now wrap copy that previously ran under the ✕.

- **A bar's card no longer paints behind the status bar.** The clearance for it was the content
  wrapper's top padding, which held the design clear of the clock but let the card's own
  background run up behind it. Invisible for as long as a bar had no background — which is every
  banner before `cardBackgroundColor` — and plainly wrong once one does, worst on a card the
  author narrowed: a 240dp centred card was photographed with the clock and battery drawn over
  it. The clearance is now the card's top margin. **The content does not move**: it was at
  `statusBarPad + 12` inside a card at y=0 and is at `12` inside a card at y=`statusBarPad`, and
  the card's bottom is unchanged, so a default bar — which is transparent — draws exactly what it
  drew before.

- **A card the author SIZED is laid out inside the system bars rather than under them.** A popup
  or flyout given a `cardWidth`/`cardHeight` was placed against the DISPLAY's edges, so a
  bottom-anchored card put its last rows behind the navigation bar and a `cardOffsetVertical`
  meant to lift it clear bought nothing — the edge it measures from was itself under the bar. A
  sized axis is now inset on both of its edges, so an anchored card lands on the bar's inner edge
  and a centred one is centred in what a person can see. An unsized axis stays `MATCH_PARENT` and
  stays full-bleed, which is what a takeover popup is; a flyout's own 80% width is not an authored
  size and keeps its background flush with the edge it is docked to. On a CENTRED axis the inset
  applied is half the difference between the two bars, not both of them: `FrameLayout` adds
  `topMargin - bottomMargin` whole when it centres, so equal-to-the-insets margins would overshoot
  the visible centre by exactly as much as no inset at all undershot it. The content's own
  padding is dropped on an axis the card is inset on, for the same reason: the card is already
  clear of that bar, and padding for it twice held `contentVerticalAlign: "bottom"` 63dp short of
  the card's bottom edge. The padding is dropped only where the card is provably CLEAR of that
  bar — sized on the axis, fitting inside the visible box, and not translated along it by an
  offset — because a sized card is not necessarily a clear one: `cardHeight: "100%"` covers both
  bars exactly as an unsized card does.

### Changed

- **Honoured nine `cssStyles` chrome and position keys the API already serves**
  (`cardBackgroundColor`, `cardWidth`, `cardHeight`, `cardBorderRadius`, `contentVerticalAlign`,
  `cardPositionVertical`, `cardPositionHorizontal`, `cardOffsetVertical`,
  `cardOffsetHorizontal`), which this SDK previously ignored on the three display types that
  draw a card of their own (`popup`, `bar`, `flyout`). A value equal to its documented default
  changes nothing, so a banner whose author never opened these controls — every banner in
  production before this release — renders exactly as it did in 1.5.2 **except for the three
  chrome-layout changes listed under Fixed below**, which affect every bar and every popup
  because the defects they close predate these keys. `displayPosition` keeps
  the role it has today: it is what `cardPositionVertical`/`cardPositionHorizontal` fall back to
  on the bar's vertical axis and the flyout's horizontal one, and on no other. The two offsets
  translate the card rather than inset or pad it, so a positive value moves it away from the edge
  it is anchored to and down or right on an axis where it is centred — the same displacement, and
  the same sign, as the Swift, React Native and Flutter SDKs.
- **The popup's close button is now a sibling of the card, not a child of it, and tracks the
  card's own laid-out corner.** The popup dialog is deliberately not dismissable any other way,
  and a card the author has given a `cardWidth` to can be narrower than the button, which would
  leave part of it outside the card and untappable — `ViewGroup.dispatchTouchEvent` only forwards
  a pointer to a child it falls inside. Being a sibling of the card keeps it reachable at any card
  size; being positioned from the card's own top-right corner — via an `OnLayoutChangeListener` on
  the card, rather than the gravity a true child could use — keeps it visually attached to the card
  instead of the window, so a card the author has sized or centred keeps its close button attached
  to it rather than leaving it floating in the screen's corner with nothing behind it (the popup
  path has no scrim). Still clamped into the window's safe area, so it cannot be pushed off screen
  or under a system bar. At the default the card fills the window, so the button is laid out
  exactly where it was in 1.5.2.
- **The popup card no longer takes its background colour from the Unlayer design's
  `popupBackgroundColor`.** That key was always an editor default (our editor never shows the
  Popup Builder that would let an author set it), not authored intent, and `cardBackgroundColor`
  now owns the property. Behaviour visible only to an account that had somehow authored
  `popupBackgroundColor` directly on the design JSON outside the normal editor flow: that popup
  now renders with the `cardBackgroundColor` default (white) instead.

## 1.5.2

Released 22 September 2026. PATCH: the fix below changes behaviour but adds and deprecates no
public API. Verified on a physical Android device before tagging — the row it closes is
"BAN-16 dark mode and rotation", whose banner vanished on rotation against 1.5.1 and survives
against this; section J was re-run whole (16 pass, 0 fail) to confirm the once-per-session
behaviour 1.5.1 introduced is unchanged, BAN-13, BAN-14 and BAN-15 included.

### Fixed

- **A banner on screen was destroyed by a configuration change and never came back.** A
  rotation, a dark-mode toggle, a font- or display-size change, a locale change or a
  multi-window resize destroys the host, and `BannerDisplayManager`'s lifecycle observer
  responds to `ON_DESTROY` by dismissing everything it is showing. The recreated host
  re-attached with nothing on screen, and nothing put the banner back: the banner flow the
  manager collects has no replay, so a fresh collector sees only what is emitted after it
  subscribes, and the 1.5.1 session store has already recorded the banner as shown, so the
  next screen view will not re-emit it either. The user lost the banner for the rest of the
  session. What the outgoing host has on screen is now handed to the host instance that
  replaces it through a `ViewModel` in the host's own `ViewModelStore` — the platform's own
  mechanism for state that outlives a configuration change and nothing else. A host that is
  really finished has its store cleared, so its banners go with it and cannot reappear
  anywhere; two live instances of the same Activity class have stores of their own and
  restore only their own banners; and a host that attaches two managers watching different
  `targetSelector`s keeps a hand-off for each. The hand-off is written from the host
  *activity's* `ON_DESTROY`, which is what separates a recreation from a navigation for a
  fragment-hosted manager: a fragment loses its view to both, but only the activity going
  away means the screen and everything on it is being rebuilt. A fragment put on the back
  stack hands nothing over, and detaching removes the observer, so a configuration change
  while the user is on another screen has nothing to hand over either — the 1.5.1
  once-per-session behaviour is unchanged, and a restore is reachable only by the owner
  being recreated. A restored banner is put back exactly as it went up, whatever its
  `displayType`; it is not re-displayed, so it is neither marked shown a second time nor
  counted as a second impression. A rotation is the same impression of the same banner.

## 1.5.1

Released 22 September 2026. PATCH: the fix below changes behaviour but adds and deprecates
no public API. Verified on a physical Android device before tagging — the row it closes is
"BAN-13 a one-time banner is shown once per session", which failed against 1.5.0 and passes
against this.

### Fixed

- **A one-time banner showed again every time the user came back to the screen.**
  `BannerManagerService.initialize()` cleared its own set of already-displayed banner
  tokens, and the host app calls `initialize(response.banners, …)` on every push response
  that carries banners — that is, on every screen view. The set therefore deduplicated only
  within a single response and nothing remembered that a banner had already been shown, so
  navigating away and back displayed it a second time. Found on the React Native SDK, whose
  banner path has the same shape; on Android the same code is reached by any navigation that
  tracks a screen view. The shown tokens now live in a session-scoped store that
  `initialize()` leaves alone and `SessionService` clears when a new session starts — a cold
  start, or a return to the foreground after more than 30 minutes in the background.
  `initialize()` still replaces the banner list and cancels the previous response's delay and
  scroll timers. Server-side suppression is unchanged: it is keyed on a banner *click*, so a
  banner that was shown and not clicked is still re-delivered in the next response — the SDK
  is now what stops it from being displayed twice. A banner is marked shown once
  `BannerDisplayManager` actually renders it, not when `BannerManagerService` merely emits it
  for display — a banner that was emitted with no collector attached, dropped by a full
  display buffer, or filtered out on the display side (no design, a custom `displayType`, or a
  static banner whose `cssSelector` doesn't match) is retried on the next trigger instead of
  being suppressed for the rest of the session with nothing ever having been shown.

## 1.5.0

Released 16 September 2026. MINOR rather than the PATCH originally planned: the NPS fix
below adds `NpsDialogFragment.newInstance(NpsConfig)` as new public API and deprecates the
three-argument overload, which `RELEASING.md` classifies as MINOR regardless of what else
ships alongside it.

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
  only discarded a merge a later push still had to send; and an id already queued is not queued
  again, so A → B → A → B queues `["A", "B"]` rather than `["A", "B", "A"]` (matching the Swift
  SDK's dedupe — Swift's clearing behaviour below is not matched, see next).

  `skipMergeWithPreviousProfileId` (the logout path) now clears the queue only when the id passed
  with it differs from the stored one. A queued id is delivered against `profile.id` as it stands
  *at push time*, so keeping one past a real logout would not preserve the old link (its other
  half is already gone) but would merge the signed-out user into the anonymous session — that case
  still clears. The same flag with the id already stored is not a logout but a host re-asserting
  its stored id at every initialisation; clearing there wiped the queue at the relaunch step and
  left the durable queue with nothing to deliver, before this fix. This is a deliberate divergence
  from the Swift SDK, which still clears unconditionally on that flag; Swift is not fixed by this
  change.

  The wire format is unchanged. `profileChanged` is now sent as `true` whenever the body carries
  merge ids, which is the only combination of the two the backend has ever received — before the
  queue was durable, an id could not outlive the flag.

- **`enablePushNotifications` gated nothing, so a config that disables push still registered a
  push token.** Nothing in the SDK read the flag, while the Swift SDK reads it in five places,
  so `trackingOnly()` and `messagingOnly()` — both of which set it to `false` — registered a
  token against the integrator's profile exactly like `pushOnly()` did. Device-verified on a
  Xiaomi 2407FPN8EG (Android 16): one `POST /api/v0/appPush/tokens` per preset, whatever the
  flag said. `registerPushToken` is now a silent no-op when the flag is false, returning before
  it touches storage or the network, the same shape as the existing `enableTracking` guards —
  so it no longer throws the missing-deviceId/profileId errors for a merely disabled feature
  either. With the flag true nothing changes, including those errors. `enableScreenTracking`,
  `enableInAppMessaging` and `enableAnalytics` are still read by nothing on either SDK and are
  deliberately left alone.

### Documentation

- **README: a "Data safety (Google Play)" section.** The SDK shipped no privacy documentation
  at all, so an integrator filling in Play's Data safety form had nothing to go on — and unlike
  Apple's `PrivacyInfo.xcprivacy`, which the Swift SDK ships and Apple aggregates, Play's form is
  declared per app in Play Console with nothing in the AAR feeding it, so documentation is the
  only deliverable here. The new section lists each collected data type against the call site
  that sends it, the purposes to declare (with the NPS free-text comment kept narrower than the
  behavioural types), the collected-versus-shared line and the caveat that conversion forwarding
  is Releva-side configuration rather than a call site here, encryption in transit, the absence
  of any deletion API, and what remains the integrator's own. It also flags the advertising-ID
  trap: this SDK declares only `INTERNET` and `ACCESS_NETWORK_STATE` and keeps
  `firebase-messaging` `compileOnly`, but Firebase Analytics — which most apps that set up FCM
  also add — merges `AD_ID` into the release manifest, as observed in the example app, and Play
  flags a release whose permissions contradict its Data safety answers.

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

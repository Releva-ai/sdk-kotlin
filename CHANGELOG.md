# Changelog

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

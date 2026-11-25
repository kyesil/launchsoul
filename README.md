# Better Launch Soul

A companion for the home screen your phone already has. It does not replace the launcher: it adds swipe gestures, a fast search and a few automations on top of it.

The app has no internet permission. The interface is Turkish only for now.

**Status:** test version, used daily on an Honor Magic 8 Pro (MagicOS 11), also tested on a Galaxy S21 (One UI 7) and a Pixel emulator (Android 16). Not on any store; APKs are on the [releases page](https://github.com/kyesil/launchsoul/releases).

## Features

### Home screen gestures

- Swipe down on the left for notifications, on the right for quick settings, swipe up for search. Every action can be changed.
- The areas are adjustable: strips at the sides and a bottom area up to the middle of the screen.
- Double tap on an empty place of the bottom area locks the screen.
- Edge handles that also work inside other apps.

### Search

- Apps, contacts, phone settings, sums, reminders and the notification history.
- The matching part is bold. When a contact was found by another field, that field is shown.
- Contacts with photo, call and message buttons; phone or WhatsApp for each.
- Long press an app and choose Find to see where it is on the home screen.

### Automations

- **Sleep mode at home:** Do Not Disturb at night, only on your home Wi-Fi.
- Notification history and rules, reminders, a sleep timer.

## Permissions

All optional; each one only turns on its own feature.

| Permission | Used for |
|---|---|
| Accessibility | Gestures, locking, Find. Looks at which window is in front, at the icon you touch on the home screen and, for Find only, at the icon names there (on Honor also at the Honor Search screen). Content of other apps is not read. |
| Notification access | History, rules, pausing media |
| Contacts, phone | Contact search, direct calls |
| Do Not Disturb access | Sleep mode at home |
| Location (always) | Only to read the name of the Wi-Fi network; not needed when the network is saved without a name |
| Usage access | Recently and most used apps |
| Alarms, notifications | Reminders, sleep timer |

### Privacy

- Nothing is sent anywhere by this app and there is no analytics. What you tap can of course open another app with your text, such as the web search.
- On Honor phones, Find types the app's name into Honor Search, which may look it up online.
- The debug log is off by default. It never holds notification texts, contacts, searches or reminders.

## Limits

- Android does not let a touch move from one window to another. Inside an area a page does not follow the finger, and to drag an icon you long press, let go at the vibration, then long press again.
- Two calls are not part of the public SDK: `StatusBarManager.expandNotificationsPanel` and `expandSettingsPanel`. If they fail, accessibility is used instead.
- Honor phones need "manage manually" for the app in the battery settings.

## Build

JDK 17 or newer and the Android SDK (platform 37).

```sh
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
```

The APK is in `app/build/outputs/apk/release/`. Without a `keystore.properties` it is signed with your local debug key, so it cannot update an APK from the releases page.

## Code

| Folder | Content |
|---|---|
| `core` | Plain Kotlin: text matching, reminder parser (unit tested) |
| `system` | Accessibility service, home screen areas, Find, actions, debug log |
| `search` | Search screen |
| `auto` | Reminders, sleep timer, sleep mode at home |
| `notif` | Notification history and rules |
| `data`, `ui`, `settings` | Apps and icons, theme, settings screens |

## License

[GPL-3.0](LICENSE)

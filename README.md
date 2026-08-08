# QuickNotes

QuickNotes is a fast, minimal note-taking app for Android.

## Features

- Opens the last opened note when the app starts.
- Automatically moves the cursor to the end of the note.
- Asks for permission to access a folder using Android's Storage Access Framework.
- Saves notes as plain text files.
- Autosaves while typing.
- Saves when the app goes to the background.
- No database required.
- No special storage permissions required.

## Package

```text
com.infdesk5.quicknotes
```

## Build

Make sure the Gradle wrapper is executable:

```bash
chmod +x gradlew
```

Then build:

```bash
./gradlew assembleDebug
```

The debug APK will be generated under:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

The selected folder URI and the last opened note URI are stored in app preferences.

If app data is cleared, QuickNotes forgets the last opened note, but the note files themselves remain in the selected folder.

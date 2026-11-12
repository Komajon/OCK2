# OCK 2.0 — Linux launcher for Android

OCK combines an Android terminal with a lightweight app launcher. Install a GitHub repository once, keep its launch command as a shortcut, and start it with one tap.

## Features in this build

- Dark, responsive dashboard with quick-launch tiles.
- App list with launch, edit-shortcut, and remove-shortcut actions (long-press a tile).
- GitHub ZIP installer (latest release asset when available, otherwise repository ZIP).
- Terminal screen with command history, output, shell restart, and one-tap app launch.
- Basic settings for terminal font size and history.
- Custom OCK launcher icon.
- GitHub Actions builds debug and release APKs automatically.

## Build with GitHub Actions

1. Create a GitHub repository and upload the contents of this project.
2. Push to `main` or `master`, or open **Actions → OCK Android Build → Run workflow**.
3. Open the completed workflow run and download **OCK-debug-apk** or **OCK-release-apk** from Artifacts.
4. To publish an APK in the repository's **Releases**, create a GitHub Release (for example tag `v2.0.0`). The workflow attaches the release APK automatically.

No signing secrets are required for CI. The release APK produced by this workflow is unsigned; Android may not allow it to update an already installed build signed with a different key. For public distribution, configure a permanent signing key and keep it private.

## Important limitations

- This is an Android `/system/bin/sh` environment, not a complete Linux distribution. It does not include APT, Debian packages, glibc, or a full Linux userspace.
- The GitHub installer downloads and unpacks a repository and looks for `ock.sh`, `run.sh`, `main.sh`, or a shell script in the repository root. Repositories that need compilation, dependencies, special arguments, or a different entry point may need manual setup.
- A shortcut stores a launch command; it does not make an incompatible Linux binary run on Android.
- Only install repositories you trust. A downloaded script can execute commands with the app's Android permissions.
- This project is a foundation for a later ARM64 Linux userspace (for example a proot-based Debian environment), not a replacement for Termux yet.

## App identity

Application ID: `com.ock.terminal`  
Version: `2.0.0`

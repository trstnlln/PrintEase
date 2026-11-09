# Verification

- Flutter analyzer: no issues (final source).
- Flutter tests: 5 passed (pricing, queue day boundaries, status transitions, shared demo roles, distinct queue numbers).
- Firebase emulator rule tests: 4 passed (private order access, unauthorized writes and estimate tampering, administrator workflow, private PDF ownership/MIME/size restrictions).
- Flutter web demo compilation: passed.
- Headless Chromium at a 412 × 915 viewport: student sign-in, PDF file picker, two-page PDF parsing, PHP 4 estimate, review confirmation, submission and order-list update passed with no browser page errors.
- Bundled Roboto font rendering and mobile dashboard visually checked.

The web check used the compiled demo. Subsequent source edits added control-flow braces and removed a temporary dialog controller; Flutter tests pass on that final source.

Not verified: APK compilation or Android device execution (Android SDK unavailable in the build environment); real Firebase integration (no user project configuration or credentials supplied); real-world upload/status latency on Wi-Fi/cellular. Use README.md to configure and run those checks in Android Studio.

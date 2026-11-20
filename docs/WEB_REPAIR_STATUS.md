# Web repair status — November 20, 2026

Verified using the owner-provided public Firebase configuration: authorized website domain is present; after the owner enabled Email/Password, a temporary account could register and sign in. The temporary account was deleted. The configured Firebase Storage bucket does not exist and its console requires Blaze billing. The owner chose storage without Firebase billing. The Firestore API initially reported disabled; the owner is creating/opening the existing default database.

Web fixes preserve a selected PDF when the iPhone file picker returns, read autofilled form values before rendering, keep registration/upload errors visible, and retain an in-memory PDF if local draft storage fails. Tests exercise the actual PDF parser and simulate the focus/autofill/storage-failure cases.

The coding environment became unavailable before these fixes could be built or published to Sites. GitHub Actions verifies the source. The no-Firebase-billing hosted PDF backend and its Android integration are in progress; the live website has not been switched to it. Do not interpret a source commit as a successful website deployment.

To complete Firestore setup, open the existing `(default)` database and publish the current `firebase/firestore.rules` from this branch. Those rules support immutable hosted PDF hashes and preserve per-student order privacy. `admins/<Firebase UID>` with `enabled: true` can be created only through the trusted console; clients cannot grant themselves that role. The clients' role mapping will be added with the hosted-storage integration.

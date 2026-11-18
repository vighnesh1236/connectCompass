# CampusConnect

CampusConnect is a Kotlin/Jetpack Compose campus companion backed by an Express REST API and PostgreSQL. It includes student registration and sign-in, a student dashboard, announcements, event registration, assignment progress, private student messaging, profile editing, and role-protected admin management.

## Project layout

```text
app/                 Android application (Compose, Retrofit, MVVM)
backend/             Express API and PostgreSQL migration
README.md            Setup, deployment, and API reference
```

## Backend: PostgreSQL and local run

1. Install Node.js 20 or newer and create a PostgreSQL database. For Neon, copy the pooled or direct connection string from the project dashboard and keep `sslmode=require` in the URL.
2. In `backend/`, copy `.env.example` to `.env`, then set `DATABASE_URL` and a random `JWT_SECRET` of at least 32 characters. Do not commit `.env`.
3. Run:

   ```sh
   cd backend
   npm install
   npm run migrate
   npm run dev
   ```

4. `GET http://localhost:3000/health` reports API and database connectivity. Migrations are repeatable; `npm start` runs them automatically before starting the API, and `npm run migrate` can also be run manually.

New accounts always receive the `student` role. To grant administrator access, register the account normally, then promote that user's row through the Neon SQL editor using a trusted operator account:

```sql
UPDATE users SET role = 'admin' WHERE email = 'admin@your-college.edu';
```

The user must sign in again after promotion to receive an admin JWT. Never expose database credentials or JWT secrets in the Android app.

The app requests internet access, camera access (only after the student chooses to take a photo), and Android's notification permission (after sign-in). Choosing an existing profile photo uses Android's system photo picker and does not request broad storage access. Private in-app student messaging does not read or send SMS or place calls, so the app does not request call or SMS permissions.

Students can attach one PDF (up to 10 MB) to each assignment. Android's system document picker grants access only to the selected file; no broad media/storage permission is needed. PDFs are stored in PostgreSQL and can be replaced or opened from the assignment card. Admins can target assignments and exam dates to a department, or leave the department blank to publish to all branches. Enter the official college exam calendar and branch-specific subjects in Admin studio; these vary by university, so the app does not invent dates or claim a universal B.Tech subject list. Admins can create dated and timed Sanskrit/campus-fest or hackathon events in the event calendar.

## Render deployment

Use the included `render.yaml` Blueprint or create a Render Web Service with **Root Directory** `backend`, **Build Command** `npm install`, and **Start Command** `npm start`. Set `DATABASE_URL` to the Neon connection string. The service runs the repeatable schema migration automatically on startup. Render's health check path is `/health`. The Blueprint generates a `JWT_SECRET`; set `CORS_ORIGINS` only for any browser clients.

The Android client uses HTTPS and does not need a browser CORS origin. Configure the actual Render service URL at build time as described below; this repository does not contain cloud account credentials, so it cannot create or claim a deployed Render service or provision a Neon database.

## Student messages and background notifications

Student accounts can start private one-to-one chats from the **Messages** tab. Chat history is stored in PostgreSQL. After sign-in, allow notifications so the app can keep a self-hosted WebSocket connection in an Android foreground service; incoming messages then produce phone notifications without Firebase or another push provider. Android displays an ongoing “CampusConnect is connected” notification while this service runs, and the connection resumes after a device restart when notifications remain enabled. This persistent connection uses battery; Android Doze or manufacturer battery-saving settings may delay alerts, and force-stopping the app disconnects it. If notifications are denied, enable them under Android **Settings → Apps → CampusConnect → Notifications**.

## Android build and installation

Open the project root in Android Studio and sync Gradle. The Android application ID is `com.vighnesh1236.campusconnect`. The app is configured by default for the deployed Render service at `https://connectcompass.onrender.com/`. To override it, configure the base URL (include the trailing slash) in the root `gradle.properties` or pass it to Gradle:

```properties
campusConnectApiBaseUrl=https://YOUR-SERVICE.onrender.com/
```

To build an installable debug APK on Windows:

```powershell
.\gradlew.bat assembleDebug -PcampusConnectApiBaseUrl=https://YOUR-SERVICE.onrender.com/
```

The installable APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Transfer it to an Android device, allow installation from that file source when prompted, then install. `assembleRelease` currently emits an unsigned APK at `app/build/outputs/apk/release/app-release-unsigned.apk`; configure a private release keystore before distributing a signed release. Never check keystores or passwords into source control.

## API reference

All routes except `GET /health`, registration, and login require `Authorization: Bearer <JWT>`. Responses use JSON; deletes return `204`. Error responses include a `message`; validation errors also include an `errors` array. The student role is assigned at registration and cannot be selected by the client.

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/health` | Public | API and database readiness |
| POST | `/api/auth/register` | Public | Register a student (`name`, `email`, `password`, `rollNumber`, `department`) |
| POST | `/api/auth/login` | Public | Sign in (`email`, `password`) |
| GET, PUT | `/api/students/profile` | Student/admin | View or update name, roll number, department |
| PUT | `/api/students/profile/photo` | Student/admin | Set or clear a JPEG profile photo (`profilePhoto`, nullable data URL) |
| DELETE | `/api/students/profile/photo` | Student/admin | Remove the profile photo |
| GET | `/api/messages/students` | Authenticated student | List students available for a direct chat |
| GET, POST | `/api/messages/conversations` | Authenticated student | List conversations or start one with `studentId` |
| GET, POST | `/api/messages/conversations/:id/messages` | Conversation participants | Read messages or send a message (`body`) |
| WebSocket | `/api/messages/live` | Authenticated student | Receive live notifications (send a JSON `authenticate` message with the JWT after connecting) |
| GET | `/api/announcements` | Student/admin | List latest announcements first |
| POST, PUT, DELETE | `/api/announcements[/:id]` | Admin | Create, update, and delete announcements |
| GET | `/api/events` | Student/admin | List events and this user's registration state |
| GET | `/api/events/:id` | Student/admin | Event details |
| POST, DELETE | `/api/events/:id/register` | Student/admin | Register or cancel (duplicate registration returns `409`) |
| GET | `/api/events/my-registrations` | Student/admin | List the signed-in user's registrations |
| POST, PUT, DELETE | `/api/events[/:id]` | Admin | Create, update, and delete events |
| GET | `/api/assignments` | Student/admin | List assignments with this user's status |
| POST | `/api/assignments/:id/submission` | Student/admin | Upload or replace a PDF assignment submission (multipart `file`, max 10 MB) |
| GET | `/api/assignments/:id/submission` | Owner only | View/download the signed-in student's PDF submission |
| PUT | `/api/assignments/:id/status` | Student/admin | Set status to `pending` or `completed` |
| POST, PUT, DELETE | `/api/assignments[/:id]` | Admin | Create, update, and delete assignments |
| GET | `/api/exams` | Student/admin | List exam dates for the student's department and all-branch dates |
| GET | `/api/admin/exams` | Admin | List all configured exam dates |
| POST, PUT, DELETE | `/api/admin/exams[/:id]` | Admin | Manage Mid Semester 1, Mid Semester 2, and Final Semester dates by department |
| GET | `/api/admin/students` | Admin | List student accounts |
| GET | `/api/admin/events/registrations` | Admin | List event registrations and student details |
| GET | `/api/admin/dashboard` | Admin | Summary counts |

## Architecture and project demonstration

The Android UI is composed in Material 3. `CampusViewModel` owns screen state and performs coroutine-based API operations; `ApiClient` configures Retrofit and attaches the session's encrypted JWT. The foreground messaging service reconnects to the authenticated WebSocket endpoint for background alerts, without requiring Firebase; Android shows a persistent notification while this connection is active. Profile photos are resized/compressed on-device and stored with the user's PostgreSQL profile. The app uses Android's system photo picker and camera app for profile photos, so it does not need broad photo-library or storage access. The Express routes validate request bodies, hash passwords with bcrypt, verify JWTs, enforce admin roles, and use parameterized PostgreSQL statements. The schema defines foreign keys and unique constraints for email, roll numbers, event registrations, and per-student assignment status.

For a demonstration, register a student, sign in, browse the live campus feed, register for an event, and mark an assignment complete. Promote a separate account to admin in Neon, sign in again, and use the Admin studio to publish announcements/events/assignments and inspect student accounts and event registrations.

## Screenshots

Screenshots are not checked in because the connected phone restored a personal account during capture. Capture presentation screenshots with a dedicated test account or emulator.

# CampusConnect

CampusConnect is a Kotlin/Jetpack Compose campus companion backed by an Express REST API and PostgreSQL. It includes student registration and sign-in, a student dashboard, announcements, event registration, assignment progress, profile editing, and role-protected admin management.

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

4. `GET http://localhost:3000/health` reports API and database connectivity. Migrations are repeatable and may also be run with `npm run migrate` after deployment.

New accounts always receive the `student` role. To grant administrator access, register the account normally, then promote that user's row through the Neon SQL editor using a trusted operator account:

```sql
UPDATE users SET role = 'admin' WHERE email = 'admin@your-college.edu';
```

The user must sign in again after promotion to receive an admin JWT. Never expose database credentials or JWT secrets in the Android app.

## Render deployment

Use the included `render.yaml` Blueprint or create a Render Web Service with **Root Directory** `backend`, **Build Command** `npm install`, and **Start Command** `npm start`. Set `DATABASE_URL` to the Neon connection string, then run `npm run migrate` from the backend shell once before using the service. Render's health check path is `/health`. The Blueprint generates a `JWT_SECRET`; set `CORS_ORIGINS` only for any browser clients.

The Android client uses HTTPS and does not need a browser CORS origin. Configure the actual Render service URL at build time as described below; this repository does not contain cloud account credentials, so it cannot create or claim a deployed Render service or provision a Neon database.

## Android build and installation

Open the project root in Android Studio and sync Gradle. The app is configured by default for the deployed Render service at `https://connectcompass.onrender.com/`. To override it, configure the base URL (include the trailing slash) in the root `gradle.properties` or pass it to Gradle:

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
| GET | `/api/announcements` | Student/admin | List latest announcements first |
| POST, PUT, DELETE | `/api/announcements[/:id]` | Admin | Create, update, and delete announcements |
| GET | `/api/events` | Student/admin | List events and this user's registration state |
| GET | `/api/events/:id` | Student/admin | Event details |
| POST, DELETE | `/api/events/:id/register` | Student/admin | Register or cancel (duplicate registration returns `409`) |
| GET | `/api/events/my-registrations` | Student/admin | List the signed-in user's registrations |
| POST, PUT, DELETE | `/api/events[/:id]` | Admin | Create, update, and delete events |
| GET | `/api/assignments` | Student/admin | List assignments with this user's status |
| PUT | `/api/assignments/:id/status` | Student/admin | Set status to `pending` or `completed` |
| POST, PUT, DELETE | `/api/assignments[/:id]` | Admin | Create, update, and delete assignments |
| GET | `/api/admin/students` | Admin | List student accounts |
| GET | `/api/admin/events/registrations` | Admin | List event registrations and student details |
| GET | `/api/admin/dashboard` | Admin | Summary counts |

## Architecture and project demonstration

The Android UI is composed in Material 3. `CampusViewModel` owns screen state and performs coroutine-based API operations; `ApiClient` configures Retrofit and attaches the session's encrypted JWT. The Express routes validate request bodies, hash passwords with bcrypt, verify JWTs, enforce admin roles, and use parameterized PostgreSQL statements. The schema defines foreign keys and unique constraints for email, roll numbers, event registrations, and per-student assignment status.

For a demonstration, register a student, sign in, browse the live campus feed, register for an event, and mark an assignment complete. Promote a separate account to admin in Neon, sign in again, and use the Admin studio to publish announcements/events/assignments and inspect student accounts and event registrations.

## Screenshots

Screenshots are not included because no Android emulator or device capture was available in this workspace. Capture the running app from Android Studio or a physical device for the project presentation.

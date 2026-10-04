# Deploying TypeRush

- Backend → Render web service (Docker Hub image built by GitHub Actions)
- Frontend → Vercel
- Database → shared MySQL host

The password lives only in Render's environment and GitHub secrets. Nothing sensitive is committed.

## Database compatibility

The schema deliberately avoids `DATETIME(6)`, so it also works on old MySQL 5.5 hosts. Verified against
MySQL 5.5.62: tables create, 12 texts seed, and race results persist.

Two limitations of very old hosts:

- Tables inherit the server's default charset. If your host reports `latin1`, nicknames outside
  Latin-1 (CJK, emoji) will fail to save; the race itself still completes. A MySQL 8 host removes this.
- Shared free hosts are slow and occasionally drop connections at startup. The app retries, but the
  first boot can log a `Communications link failure` before succeeding.

---

## 1. Docker Hub

1. Create a Docker Hub account.
2. Account Settings → Personal access tokens → Generate new token, scope **Read & Write**.
3. Create a repository named `type-race-backend` (or set a repo variable `IMAGE_NAME` instead, see below).

## 2. Push the backend to GitHub

Already done — the repo is live at `github.com/ashutoshkumarmangal/type-race-backend`. From here on,
every push to `master` triggers the image build:

```powershell
cd E:\typing-race\backend
git add .
git commit -m "..."
git push origin master
```

Then in the repo: **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
| --- | --- |
| `DOCKERHUB_USERNAME` | your Docker Hub username |
| `DOCKERHUB_TOKEN` | the token from step 1 |
| `RENDER_DEPLOY_HOOK` | added later, after the Render service exists (optional) |

Optional: **Settings → Secrets and variables → Actions → Variables** → `IMAGE_NAME` =
`type-race-backend` (defaults to this if unset).

Every push to `master` now builds the image and pushes `:master`, `:sha-<commit>` and, on the first
push, `:latest`. Check progress under the repo's **Actions** tab. Nothing is pushed unless the build
succeeds, so Render never sees a broken image.

## 3. Create the Render service from that image

Render can only be told "use this image" through its API (a blueprint cannot express it).

1. Render dashboard → Account Settings → API Keys → Create, copy `rnd_...`
2. PowerShell:

```powershell
$headers = @{ Authorization = 'Bearer rnd_YOUR_KEY'; 'Content-Type' = 'application/json' }

# Fill in the four DB values below. Do not commit them anywhere.
$db = @{
  host = 'your-mysql-host'
  port = 3306
  name = 'your-db-name'
  user = 'your-db-user'
  pass = 'your-db-password'
}
$jdbc = "jdbc:mysql://$($db.host):$($db.port)/$($db.name)?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"

$body = @{
  name    = 'type-race-backend'
  image   = @{ owner = 'YOUR_DOCKERHUB_USERNAME'; imageName = 'type-race-backend'; tag = 'master' }
  plan    = 'free'
  region  = 'singapore'
  envVars = @(
    @{ key = 'DB_URL';       value = $jdbc }
    @{ key = 'DB_USER';      value = $db.user }
    @{ key = 'DB_PASSWORD';  value = $db.pass }
    @{ key = 'CORS_ORIGINS'; value = 'https://YOUR-FRONTEND.vercel.app' }
    @{ key = 'DB_POOL_SIZE'; value = '5' }
  )
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Uri 'https://api.render.com/v1/services' -Method Post -Headers $headers -Body $body
```

`PORT` is injected by Render automatically — do not set it. The jar reads `PORT` first and falls back
to 8081 (`backend/src/main/resources/application.yml`).

3. Note the service URL, e.g. `https://type-race-backend.onrender.com`, and verify:

```powershell
Invoke-RestMethod https://type-race-backend.onrender.com/api/health
```

## 4. Wire CI → Render

Dashboard → your service → **Settings → Deploys → Deploy hook**. Add it as the
`RENDER_DEPLOY_HOOK` secret from step 2.

The workflow POSTs that hook after a successful push. Because the tag is the commit SHA, each
deploy genuinely re-pulls the new image rather than reusing a cached layer.

Check the service's **Events** tab to see which tag was deployed.

## 5. Frontend on Vercel

`E:\typing-race\frontend` is set up for Vercel (`vercel.json` pins the Vite build and the SPA fallback):

1. Vercel → Add New → Project → import `ashutoshkumarmangal/type-race-fe`. Framework preset: Vite.
2. Settings → Environment Variables (apply to **Production**, and Preview if you want previews working):

| Key | Value |
| --- | --- |
| `VITE_API_BASE` | `https://type-race-backend.onrender.com` |
| `VITE_WS_URL` | `wss://type-race-backend.onrender.com/ws/game` |

3. Deploy. Vite inlines these at build time, so after changing them press **Redeploy** — editing the
   variable alone does not rebuild the bundle.

Then set `CORS_ORIGINS` on the Render backend to the exact Vercel origin
(`https://<your-project>.vercel.app`, no trailing slash) and restart the backend, or the WebSocket
handshake will be rejected by the browser.

Leave the variables blank if the frontend is ever served from the same origin as the API.

## 6. End-to-end check

```powershell
$env:BASE='https://type-race-backend.onrender.com'
node scripts\race-smoke.mjs
node scripts\race-smoke.mjs --cheat
Remove-Item Env:BASE
```

Both must print `PASS`. On a cold free instance the first run may take ~30-60s while Render wakes up.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `Access denied for user` in the Render logs | Wrong credentials or the DB only allows certain hosts. Test them directly: `mysql -u USER -p -h HOST -P 3306 DBNAME` |
| WS connects then drops instantly | `CORS_ORIGINS` does not match the frontend origin exactly |
| Page loads but every API call fails | `VITE_API_BASE` unset, or the frontend was not re-deployed after setting it |
| Page hangs, then works after ~1 min | Normal on the free plan — the instance was asleep |
| UI shows `socket: closed` in a loop | Backend cannot reach MySQL, so it is crashing on boot; read the service logs |
| Build fails on `mvn` | Set `JAVA_VERSION=21`; the Dockerfile pins Temurin 21 |
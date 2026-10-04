# Deploying TypeRush

- Backend → Render web service (Docker Hub image built by GitHub Actions)
- Frontend → Render static site
- Database → your shared MySQL host

The password lives only in Render's environment and GitHub secrets. Nothing sensitive is committed.

---

## 1. Docker Hub

1. Create a Docker Hub account.
2. Account Settings → Personal access tokens → Generate new token, scope **Read & Write**.
3. Create a repository named `type-race-backend` (or set a repo variable `IMAGE_NAME` instead, see below).

## 2. Push the backend to GitHub

```powershell
cd E:\typing-race\backend
git init -b master
git add .
git commit -m "backend: Spring Boot realtime typing race server"
git remote add origin https://github.com/ashutoshkumarmangal/type-race-backend.git
git push -u origin master
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
$r = @{ key = 'rnd_YOUR_KEY' } | ConvertTo-Json

$body = @{
  name           = 'type-race-backend'
  image = @{
    owner     = 'YOUR_DOCKERHUB_USERNAME'
    imageName = 'type-race-backend'
    tag       = 'master'
  }
  plan = 'free'
  region = 'singapore'
  envVars = @(
    @{ key = 'DB_URL';      value = 'jdbc:mysql://HOST:3306/DB?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8' }
    @{ key = 'DB_USER';     value = 'DB_USER' }
    @{ key = 'DB_PASSWORD'; value = 'DB_PASSWORD' }
    @{ key = 'CORS_ORIGINS'; value = 'https://YOUR-FRONTEND.onrender.com' }
    @{ key = 'DB_POOL_SIZE'; value = '5' }
  )
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Uri 'https://api.render.com/v1/services' -Method Post `
  -Headers @{ Authorization = "Bearer $r"; 'Content-Type' = 'application/json' } -Body $body
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

## 5. Frontend static site

New repo for `E:\typing-race\frontend`, then Render → New → Static Site, connect the repo:

| Setting | Value |
| --- | --- |
| Build command | `npm ci && npm run build` |
| Publish path | `dist` |

Environment variables (build time — Vite inlines them, so re-deploy after changing):

| Key | Value |
| --- | --- |
| `VITE_API_BASE` | `https://type-race-backend.onrender.com` |
| `VITE_WS_URL` | `wss://type-race-backend.onrender.com/ws/game` |

No trailing slashes. Leave them blank if the frontend is served from the same origin as the API.

`CORS_ORIGINS` on the backend must contain the exact frontend origin — including the scheme and
without a trailing path — or the WebSocket handshake is rejected by the browser.

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
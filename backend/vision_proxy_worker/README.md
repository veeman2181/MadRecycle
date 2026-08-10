# Vision proxy (Cloudflare Worker)

Same job as `backend/vision_proxy` (the AWS Lambda version): holds the Anthropic API key
server-side so it never ships inside the Android APK. The app POSTs a JPEG here with a
shared-secret header; the Worker calls Claude Haiku 4.5 with the key from its own secret
store and returns the classification. See `src/index.js`'s docstring for the exact
request/response shape.

Moved off AWS because this AWS account hit an unresolved, account-level restriction
denying both `sts:AssumeRoleWithWebIdentity` (GitHub Actions OIDC) and
`lambda:InvokeFunctionUrl` (public Function URL invocation) with a generic AccessDenied,
even on freshly created resources -- see the git history on `backend/vision_proxy` for
the full investigation. Cloudflare Workers have no equivalent auth layer to get stuck
on: a Worker is public by default, and deploys authenticate with a plain API token
instead of cross-account role assumption.

## One-time setup

### 1. Install Wrangler and log in (interactive -- only you can do this)

```powershell
cd backend/vision_proxy_worker
npm install
npx wrangler login
```

This opens a browser to authorize the CLI against your Cloudflare account (free tier is
fine -- 100,000 requests/day).

### 2. Set the two secrets

`PROXY_SHARED_SECRET` must be the **same value** already in the app's
`local.properties` (`cloudVisionProxySecret`) -- reuse it rather than rotating, so the
already-shipped app config keeps working. `ANTHROPIC_API_KEY` is your real Anthropic key.

```powershell
npx wrangler secret put PROXY_SHARED_SECRET
npx wrangler secret put ANTHROPIC_API_KEY
```

Each prompts for the value interactively (not stored in shell history or any file here).

### 3. First deploy

```powershell
npx wrangler deploy
```

The output includes the Worker's URL, e.g.
`https://ecomadison-vision-proxy.<your-subdomain>.workers.dev`. Put that in the app's
`local.properties` as `cloudVisionProxyUrl` (see `app/build.gradle.kts`), replacing the
old AWS Lambda Function URL.

## CI deploy (GitHub Actions)

`.github/workflows/deploy-vision-proxy-worker.yml` redeploys the Worker automatically on
every push to `main` that touches `backend/vision_proxy_worker/**` (also runnable
manually via the Actions tab's "Run workflow" button). It only updates the Worker's
*code* -- the two secrets above are set once in step 2 and persist across deploys; the
workflow doesn't touch them.

Unlike the AWS deploy workflow, this needs exactly **one** GitHub secret, and no
per-account IAM setup:

```powershell
$apiToken = "<create at https://dash.cloudflare.com/profile/api-tokens with the 'Edit Cloudflare Workers' template>"
gh secret set CLOUDFLARE_API_TOKEN --body $apiToken
```

Once that secret exists, pushing a change under `backend/vision_proxy_worker/` to `main`
deploys it automatically.

## Redeploying manually (fallback, if you're not using the CI workflow above)

```powershell
cd backend/vision_proxy_worker
npx wrangler deploy
```

## Rotating the shared secret

```powershell
npx wrangler secret put PROXY_SHARED_SECRET
```

Then update `cloudVisionProxySecret` in the app's `local.properties` to match, and
rebuild.

## Cost/spend safety

Cloudflare Workers' free tier (100,000 requests/day) comfortably covers any realistic
scan volume; paid tier is $5/mo for 10M requests if you outgrow it. The real spend
driver is the Anthropic API calls the Worker makes on your behalf -- set a **spend
limit on the Anthropic API key** in the Anthropic Console so a leaked shared secret or a
runaway client bug has a hard ceiling, independent of anything this proxy enforces.

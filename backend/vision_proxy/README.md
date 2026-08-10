# Vision proxy (AWS Lambda)

Holds the Anthropic API key server-side so it never ships inside the Android APK. The
app POSTs a JPEG to this Lambda's Function URL with a shared-secret header; the Lambda
calls Claude Haiku 4.5 with the key from its own environment and returns the
classification. See `lambda_function.py`'s docstring for the exact request/response
shape and the residual-risk tradeoff of the shared-secret approach.

These are plain AWS CLI steps so they don't assume which IaC tool (CDK/SAM/Terraform)
your other AWS projects use -- fold this into whichever one you already have once it's
working. Shown in both PowerShell (Windows default) and bash/Git Bash.

**PowerShell note**: every JSON policy file below is written with `Out-File -Encoding
ascii`, not the more obvious `-Encoding utf8` -- Windows PowerShell 5.1's `utf8` encoding
always prepends a UTF-8 byte-order-mark, which breaks AWS's JSON parser with
`MalformedPolicyDocument: This policy contains invalid Json` (the same gotcha
`tools/material_classifier/README.md` calls out for `kaggle.json`). `ascii` never adds a
BOM and every policy document here is plain ASCII, so it's a safe substitute.

## Generating the shared secret

`PROXY_SHARED_SECRET` below is a random string you generate once -- not something from
Anthropic or AWS. It must be set to the *same* value in two places: the Lambda's
`PROXY_SHARED_SECRET` environment variable, and `cloudVisionProxySecret` in the app's
`local.properties`. Anything long and random works; a 256-bit value is generous.

PowerShell (no extra tools needed -- uses `RNGCryptoServiceProvider` rather than the
newer `RandomNumberGenerator.Fill` static method, since Windows PowerShell 5.1 runs on
.NET Framework, which doesn't have it):

```powershell
$bytes = New-Object byte[] 32
$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
$rng.GetBytes($bytes)
[Convert]::ToBase64String($bytes)
```

bash / Git Bash (needs OpenSSL, which ships with Git for Windows):

```bash
openssl rand -base64 32
```

**Rotating it later**: generate a new value, update `local.properties`, and push the new
value to the Lambda with (this replaces the *entire* `Variables` map, so include
`ANTHROPIC_API_KEY` too, not just the secret):

```powershell
aws lambda update-function-configuration `
  --function-name ecomadison-vision-proxy `
  --environment "Variables={ANTHROPIC_API_KEY=<existing-key>,PROXY_SHARED_SECRET=<new-value>}"
```

## First-time setup

### 1. Create the Lambda execution role (one-time)

This is the role Lambda assumes *when the function runs* -- different from the deploy
role in the CI section below, which is only used when GitHub Actions *updates* the
function's code. It only needs permission to write logs, since this function doesn't
touch any other AWS resource.

**PowerShell:**

```powershell
@'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Service": "lambda.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }
  ]
}
'@ | Out-File -Encoding ascii lambda-trust-policy.json

aws iam create-role `
  --role-name ecomadison-vision-proxy-execution `
  --assume-role-policy-document file://lambda-trust-policy.json

aws iam attach-role-policy `
  --role-name ecomadison-vision-proxy-execution `
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

aws iam get-role --role-name ecomadison-vision-proxy-execution --query Role.Arn --output text
```

**bash / Git Bash:**

```bash
cat > lambda-trust-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Service": "lambda.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

aws iam create-role \
  --role-name ecomadison-vision-proxy-execution \
  --assume-role-policy-document file://lambda-trust-policy.json

aws iam attach-role-policy \
  --role-name ecomadison-vision-proxy-execution \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

aws iam get-role --role-name ecomadison-vision-proxy-execution --query Role.Arn --output text
```

The last command prints the ARN to use as `<YOUR_LAMBDA_EXECUTION_ROLE_ARN>` below. If
`create-function` immediately fails with "role cannot be assumed by Lambda," IAM hasn't
finished propagating the new role yet -- wait a few seconds and retry.

### 2. Package and create the function

**PowerShell:**

```powershell
Set-Location backend/vision_proxy
pip install -r requirements.txt -t package
Copy-Item lambda_function.py package/ -Force
Set-Location package
Compress-Archive -Path * -DestinationPath ../function.zip -Force
Set-Location ..

aws lambda create-function `
  --function-name ecomadison-vision-proxy `
  --runtime python3.12 `
  --handler lambda_function.handler `
  --zip-file fileb://function.zip `
  --role "<YOUR_LAMBDA_EXECUTION_ROLE_ARN>" `
  --timeout 10 `
  --memory-size 256 `
  --environment "Variables={ANTHROPIC_API_KEY=<your-key>,PROXY_SHARED_SECRET=<generate-a-random-one>}"

# Public HTTPS endpoint, no IAM SigV4 signing required from the Android client --
# the shared-secret header is the auth boundary instead.
aws lambda create-function-url-config `
  --function-name ecomadison-vision-proxy `
  --auth-type NONE

aws lambda add-permission `
  --function-name ecomadison-vision-proxy `
  --statement-id AllowPublicFunctionUrlInvoke `
  --action lambda:InvokeFunctionUrl `
  --principal "*" `
  --function-url-auth-type NONE
```

**bash / Git Bash:**

```bash
cd backend/vision_proxy
pip install -r requirements.txt -t package
cp lambda_function.py package/
cd package && zip -r ../function.zip . && cd ..

aws lambda create-function \
  --function-name ecomadison-vision-proxy \
  --runtime python3.12 \
  --handler lambda_function.handler \
  --zip-file fileb://function.zip \
  --role "<YOUR_LAMBDA_EXECUTION_ROLE_ARN>" \
  --timeout 10 \
  --memory-size 256 \
  --environment "Variables={ANTHROPIC_API_KEY=<your-key>,PROXY_SHARED_SECRET=<generate-a-random-one>}"

# Public HTTPS endpoint, no IAM SigV4 signing required from the Android client --
# the shared-secret header is the auth boundary instead.
aws lambda create-function-url-config \
  --function-name ecomadison-vision-proxy \
  --auth-type NONE

aws lambda add-permission \
  --function-name ecomadison-vision-proxy \
  --statement-id AllowPublicFunctionUrlInvoke \
  --action lambda:InvokeFunctionUrl \
  --principal "*" \
  --function-url-auth-type NONE
```

`create-function-url-config`'s output includes the `FunctionUrl` -- put that in the
app's `local.properties` (see `app/build.gradle.kts`) along with the same
`PROXY_SHARED_SECRET` value.

## CI deploy (GitHub Actions)

`.github/workflows/deploy-vision-proxy.yml` redeploys the Lambda automatically on every
push to `main` that touches `backend/vision_proxy/**` (also runnable manually via the
Actions tab's "Run workflow" button). It only updates the function's *code* -- the
Lambda itself, its env vars, and its Function URL must already exist from "First-time
setup" above.

The workflow authenticates to AWS via **OIDC** (GitHub mints a short-lived token, AWS
exchanges it for temporary credentials) rather than long-lived access-key secrets --
nothing durable to leak if a workflow run is ever compromised. This needs a **one-time
IAM setup that only you can do** (I don't have access to your AWS account):

**1. Check whether your account already has a GitHub OIDC provider** (likely, if any
other repo already deploys to this account this way -- creating a duplicate errors):

```powershell
aws iam list-open-id-connect-providers
```

If nothing with `token.actions.githubusercontent.com` is listed, create it:

```powershell
aws iam create-open-id-connect-provider `
  --url https://token.actions.githubusercontent.com `
  --client-id-list sts.amazonaws.com `
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

**2. Create a deploy role trusted only by this repo's `main` branch.** Replace
`<ACCOUNT_ID>` with your AWS account ID throughout.

```powershell
@'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": { "token.actions.githubusercontent.com:aud": "sts.amazonaws.com" },
        "StringLike": { "token.actions.githubusercontent.com:sub": "repo:veeman2181/MadRecycle:ref:refs/heads/main" }
      }
    }
  ]
}
'@ | Out-File -Encoding ascii trust-policy.json

aws iam create-role `
  --role-name ecomadison-vision-proxy-deploy `
  --assume-role-policy-document file://trust-policy.json
```

**3. Grant that role the one permission it needs** -- updating this specific function's
code, nothing else. Replace `<ACCOUNT_ID>` and `<REGION>` (e.g. `us-east-1`, matching
wherever you created the Lambda):

```powershell
@'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "lambda:UpdateFunctionCode",
      "Resource": "arn:aws:lambda:<REGION>:<ACCOUNT_ID>:function:ecomadison-vision-proxy"
    }
  ]
}
'@ | Out-File -Encoding ascii lambda-deploy-policy.json

aws iam put-role-policy `
  --role-name ecomadison-vision-proxy-deploy `
  --policy-name UpdateVisionProxyCode `
  --policy-document file://lambda-deploy-policy.json
```

**4. Add the role ARN and region as GitHub Actions secrets** (Settings -> Secrets and
variables -> Actions -> New repository secret in the GitHub web UI, or via `gh`):

```powershell
$roleArn = aws iam get-role --role-name ecomadison-vision-proxy-deploy --query Role.Arn --output text
gh secret set VISION_PROXY_DEPLOY_ROLE_ARN --body $roleArn
gh secret set VISION_PROXY_AWS_REGION --body "<REGION>"
```

Once those two secrets exist, pushing a change under `backend/vision_proxy/` to `main`
deploys it automatically -- no more manual zip-and-upload.

## Redeploying manually (fallback, if you're not using the CI workflow above)

### PowerShell

```powershell
Set-Location backend/vision_proxy
Copy-Item lambda_function.py package/ -Force
Set-Location package
Compress-Archive -Path lambda_function.py -DestinationPath ../function.zip -Force
Set-Location ..
aws lambda update-function-code --function-name ecomadison-vision-proxy --zip-file fileb://function.zip
```

### bash / Git Bash

```bash
cd backend/vision_proxy
cp lambda_function.py package/
cd package && zip -r ../function.zip lambda_function.py && cd ..
aws lambda update-function-code --function-name ecomadison-vision-proxy --zip-file fileb://function.zip
```

Note: the redeploy zip only replaces `lambda_function.py`, so it doesn't refresh the
`anthropic` package if `requirements.txt` changed -- rerun the full first-time-setup zip
step (which repackages everything under `package/`) after a dependency bump.

## Cost/spend safety

The Lambda's own cost is negligible (well under AWS's free tier at any realistic scan
volume). The real spend driver is the Anthropic API calls it makes on your behalf --
set a **spend limit on the Anthropic API key** in the Anthropic Console so a leaked
shared secret or a runaway client bug has a hard ceiling, independent of anything this
proxy itself enforces.

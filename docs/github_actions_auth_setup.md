# GitHub Actions GCP Authentication Setup

This guide provides the exact `gcloud` commands needed to set up Workload Identity Federation (WIF) and a Service Account so that GitHub Actions can securely authenticate to your Google Cloud project without using long-lived JSON keys.

### 1. Define Variables
First, set these variables in your terminal to easily copy-paste the rest of the commands:

```bash
export PROJECT_ID="your-gcp-project-id"        # The GCP project where BigQuery lives
export SA_NAME="github-actions-sa"            # Service account name
export POOL_NAME="github-actions-pool"        # Workload Identity Pool name
export PROVIDER_NAME="github-provider"        # Workload Identity Provider name
export GHA_REPO="your-github-username/your-repo-name"  # E.g., phaniuk111/liquidbase-bq
```

Ensure you are authenticated and pointing to the right project:
```bash
gcloud config set project $PROJECT_ID
gcloud auth login
```

### 2. Enable Required APIs
Enable the necessary APIs for authentication and BigQuery:
```bash
gcloud services enable \
  iamcredentials.googleapis.com \
  iam.googleapis.com \
  bigquery.googleapis.com
```

### 3. Create the Service Account
This is the identity that GitHub Actions will impersonate.

```bash
# Create the service account
gcloud iam service-accounts create $SA_NAME \
  --description="Service account for GitHub Actions CI/CD" \
  --display-name="GitHub Actions"

# Grant BigQuery Admin access (required to create datasets, tables, and drop datasets)
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/bigquery.admin"
```

### 4. Create the Workload Identity Pool
The pool manages external identities (like GitHub) that want to access GCP.

```bash
# Create the pool
gcloud iam workload-identity-pools create $POOL_NAME \
  --project=$PROJECT_ID \
  --location="global" \
  --display-name="GitHub Actions Pool"

# Get the full ID of the pool
export WORKLOAD_IDENTITY_POOL_ID=$(gcloud iam workload-identity-pools describe $POOL_NAME \
  --project=$PROJECT_ID \
  --location="global" \
  --format="value(name)")
```

### 5. Create the Provider in the Pool
The provider tells GCP to trust tokens issued by GitHub.

```bash
gcloud iam workload-identity-pools providers create-oidc $PROVIDER_NAME \
  --project=$PROJECT_ID \
  --location="global" \
  --workload-identity-pool=$POOL_NAME \
  --display-name="GitHub Actions Provider" \
  --attribute-mapping="google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository" \
  --issuer-uri="https://token.actions.githubusercontent.com"
```

### 6. Allow the GitHub Repo to Impersonate the Service Account
This binds the Identity Pool (filtered specifically to your repository) to the Service Account.

```bash
gcloud iam service-accounts add-iam-policy-binding $SA_NAME@$PROJECT_ID.iam.gserviceaccount.com \
  --project=$PROJECT_ID \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/${WORKLOAD_IDENTITY_POOL_ID}/attribute.repository/${GHA_REPO}"
```

---

### 🎉 Final Output: Get Your GitHub Secrets
Run this final command to get the exact value you need to paste into the `WIF_PROVIDER` secret in your GitHub Repository settings:

```bash
gcloud iam workload-identity-pools providers describe $PROVIDER_NAME \
  --project=$PROJECT_ID \
  --location="global" \
  --workload-identity-pool=$POOL_NAME \
  --format="value(name)"
```

**Add these three secrets in GitHub Repository Settings:**
1. `WIF_PROVIDER`: *(The output of the command above)*
2. `SA_EMAIL`: `$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com`
3. `BQ_PROJECT_ID`: `$PROJECT_ID`

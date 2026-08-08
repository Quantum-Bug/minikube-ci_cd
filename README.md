# Minikube CI/CD + GitOps Project

End-to-end CI/CD and GitOps pipeline on Minikube for a 3-tier application (Python backend, Java backend, Angular frontend), using **Tekton** for CI (build) and **ArgoCD** for CD (GitOps deployment).

## Architecture

Git Repo (source code + k8s manifests) — single source of truth
│
├── Tekton Pipeline (in-cluster CI)
│ Task 1: git-clone → clones repo into shared workspace
│ Task 2: build-image → docker build (via node's docker.sock), tags image with git-sha + timestamp
│ Task 3: update-manifest → updates image tag in deployment.yaml, commits + pushes to Git
│
└── ArgoCD (in-cluster CD / GitOps)
watches manifests in Git → auto-syncs Deployments/Services to cluster
sole owner of deployment state (self-heal + prune enabled)


**Key design principle:** Tekton never touches the cluster directly. It only builds images and updates Git. ArgoCD is the only component that applies changes to the cluster — clean separation of CI and CD responsibilities.

### Apps deployed (namespace: `apps`)
| App | Tech | Port |
|---|---|---|
| python-backend | Flask | 5000 |
| java-backend | Java `HttpServer` | 8080 |
| angular-frontend | Angular + nginx | 80 |

### Tekton Pipeline (namespace: `ci-cd`)
- **Pipeline:** `build-deploy-pipeline`
- **Tasks:** `git-clone` → `build-image` → `update-manifest` (chained via `runAfter`, isolated `volumeClaimTemplate` workspace per run)
- **Image build:** uses node's Docker daemon directly (`docker.sock` mount) — no external registry needed since Minikube uses the Docker driver
- **Image tagging:** `<image-name>:<git-short-sha>-<timestamp>` — unique per build, never `latest`, so ArgoCD can detect real changes
- **Git push (in `update-manifest`):** authenticates via `git-credentials` Secret, retries with `git pull --rebase` on push conflicts (handles parallel pipeline race conditions)

### ArgoCD (namespace: `argocd`)
- One `Application` per service, each pointing to that app's folder in the repo
- `syncPolicy.automated`: `prune: true`, `selfHeal: true` — fully automated GitOps
- `directory.include: "{deployment.yaml,service.yaml}"` set on all apps — restricts ArgoCD to only parse the actual k8s manifests, avoiding parse errors on unrelated files (e.g. `tsconfig.json` in the Angular folder)

## Repo structure

gitops-project/
├── python-backend/ # source + Dockerfile + deployment.yaml + service.yaml
├── java-backend/ # source + Dockerfile + deployment.yaml + service.yaml
├── angular-frontend/ # source + Dockerfile + deployment.yaml + service.yaml
├── pipeline/ # Tekton Task/Pipeline/RBAC/ServiceAccount YAMLs
└── argocd-apps/ # ArgoCD Application definitions (one per service)


## One-time setup

```bash
# Cluster
minikube start --cpus=4 --memory=8192 --driver=docker
minikube addons enable ingress
kubectl create namespace ci-cd
kubectl create namespace apps

# Tekton
kubectl apply --filename https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
kubectl apply --filename https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml

# ArgoCD
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Git credentials for Tekton (needed by update-manifest task to push commits)
kubectl create secret generic git-credentials \
  --namespace=ci-cd \
  --from-literal=username=<your-github-username> \
  --from-literal=password=<your-github-PAT> \
  --type=kubernetes.io/basic-auth

# Apply pipeline resources
kubectl apply -f pipeline/

# Apply ArgoCD applications
kubectl apply -f argocd-apps/
```

## Triggering a build (CI)

```bash
REPO_URL="https://github.com/<your-org>/<your-repo>.git"

cat <<EOF | kubectl create -f -
apiVersion: tekton.dev/v1
kind: PipelineRun
metadata:
  generateName: build-python-backend-
  namespace: ci-cd
spec:
  taskRunTemplate:
    serviceAccountName: tekton-git-sa
  pipelineRef:
    name: build-deploy-pipeline
  workspaces:
    - name: shared-workspace
      volumeClaimTemplate:
        spec:
          accessModes: ["ReadWriteOnce"]
          resources:
            requests:
              storage: 1Gi
  params:
    - name: repo-url
      value: "$REPO_URL"
    - name: app-path
      value: "python-backend"
    - name: image-name
      value: "python-backend"
    - name: manifest-path
      value: "python-backend"
EOF
```

Repeat with `app-path`/`image-name`/`manifest-path` set to `java-backend` or `angular-frontend` for the other services.

> **Note:** trigger pipelines one at a time when possible. Running all 3 in parallel is supported (retry logic handles Git push races) but is slower than sequential runs.

## Verifying the flow (GitOps proof)

```bash
# Pipeline status
kubectl get pipelineruns -n ci-cd

# Confirm Tekton pushed a commit
git pull && git log --oneline -5

# ArgoCD picked it up
kubectl get applications -n argocd
kubectl get pods -n apps

# Confirm the running image is the new unique tag, not "latest"
kubectl get deployment python-backend -n apps -o jsonpath='{.spec.template.spec.containers[0].image}'
```

## Live GitOps self-heal demo

```bash
# Edit any deployment.yaml manually, e.g. change replicas: 1 -> 2
git add python-backend/deployment.yaml
git commit -m "Scale python-backend to 2 replicas"
git push origin main

kubectl patch application python-backend -n argocd --type merge \
  -p '{"metadata":{"annotations":{"argocd.argoproj.io/refresh":"hard"}}}'

kubectl get pods -n apps -l app=python-backend --watch
```
No `kubectl apply` was run — ArgoCD detects the Git change and reconciles the cluster automatically.

## Logs

Consistent labels (`app`, `tier`) on every Deployment allow filtering:
```bash
kubectl logs -n apps -l app=python-backend --tail=20
kubectl logs -n apps -l app=java-backend --tail=20
kubectl logs -n apps -l app=angular-frontend --tail=20
```
Tekton step-level logs (per Task, per pod):
```bash
kubectl logs -n ci-cd <pod-name> --all-containers
```

## Access UIs

```bash
# Tekton Dashboard
kubectl proxy --port=8080 &
# http://localhost:8080/api/v1/namespaces/tekton-pipelines/services/tekton-dashboard:http/proxy/

# ArgoCD UI
kubectl -n argocd port-forward svc/argocd-server 8081:443 &
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d; echo
# https://localhost:8081  (user: admin)
```

## Known trade-offs (documented deliberately)

- **Image build via `docker.sock`** instead of Kaniko + external registry — valid for Minikube's Docker driver, avoids registry auth complexity. Production would use Kaniko + a real registry (ECR/GCR/Docker Hub).
- **`ReadWriteOnce` workspace PVC per PipelineRun** — required because a shared PVC across parallel pipeline runs caused clone conflicts (`destination path already exists`).
- **Git push race conditions** across parallel builds are handled with a `pull --rebase` + retry loop in the `update-manifest` task. In production, a separate "manifests" repo (decoupled from app source) would reduce contention further.

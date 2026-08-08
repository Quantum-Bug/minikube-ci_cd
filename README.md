# CI/CD and GitOps Setup on Minikube — Tekton + ArgoCD

A Minikube-based CI/CD and GitOps workflow deploying three applications — a **Python (Flask) backend**, a **Java (Spring Boot) backend**, and an **Angular frontend (nginx)** — built via an in-cluster **Tekton** pipeline and deployed through **ArgoCD** GitOps automation.

> Full formal write-up, including architecture, troubleshooting, and a live GitOps self-heal demonstration, is available in [`Project_Report`](./CICD_GitOps_Report.pdf).

---

## Architecture

```
Git Repository (source + Dockerfiles + k8s manifests + ArgoCD Application specs)
        |
        |-- Tekton Pipeline (namespace: ci-cd)
        |        git-clone -> build -> image in Minikube's internal Docker daemon
        |        (imagePullPolicy: Never — no external registry required)
        |
        `-- ArgoCD Application (namespace: argocd)
                 watches <app-name>/ path on `main`
                 auto-sync + self-heal -> Deployment + Service in namespace: apps
```

| Component | Namespace | Notes |
|---|---|---|
| Tekton Pipelines + Dashboard | `tekton-pipelines`, `ci-cd` | Builds all 3 images via one reusable Pipeline |
| ArgoCD | `argocd` | Auto-sync + self-heal enabled per Application |
| `python-backend` | `apps` | Flask, port `5000` |
| `java-backend` | `apps` | Spring Boot, port `8080` |
| `angular-frontend` | `apps` | nginx, port `80`, exposed via NodePort |

---

## Repository Layout

```
python-backend/       Dockerfile, app.py, requirements.txt, deployment.yaml, service.yaml
java-backend/          Dockerfile, Main.java, deployment.yaml, service.yaml
angular-frontend/      Dockerfile, angular.json, src/, public/, deployment.yaml, service.yaml
argocd-apps/           python-backend.yaml, java-backend.yaml, angular-frontend.yaml
pipeline/              Tekton Pipeline + Task definitions, ServiceAccount
```

---

## Prerequisites

- Minikube (Docker driver)
- `kubectl`
- A GitHub repository (this one) reachable by ArgoCD

---

## Setup — From a Clean Checkout

### 1. Cluster + namespaces
```bash
minikube start --cpus=4 --memory=8192 --driver=docker
minikube addons enable ingress
kubectl create namespace ci-cd
kubectl create namespace apps
eval $(minikube docker-env)
```

### 2. Install Tekton
```bash
kubectl apply --filename https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml
kubectl apply --filename https://storage.googleapis.com/tekton-releases/dashboard/latest/release.yaml
kubectl get pods -n tekton-pipelines --watch
```

### 3. Install ArgoCD
```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl get pods -n argocd --watch

# UI access
kubectl -n argocd port-forward svc/argocd-server 8081:443 &
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d; echo
# open https://localhost:8081  (user: admin)
```

### 4. Build all three images (Tekton)
```bash
REPO_URL="<this-repo-url>"

for app in "python-backend python-backend python-backend" \
           "java-backend java-backend java-backend" \
           "angular-frontend angular-frontend angular-frontend"; do
  set -- $app
  path=$1; image=$2; deploy=$3
  cat <<EOF | kubectl create -f -
apiVersion: tekton.dev/v1
kind: PipelineRun
metadata:
  generateName: build-$path-
  namespace: ci-cd
spec:
  taskRunTemplate:
    serviceAccountName: tekton-deployer
  pipelineRef:
    name: build-deploy-pipeline
  params:
    - name: repo-url
      value: "$REPO_URL"
    - name: app-path
      value: "$path"
    - name: image-name
      value: "$image"
    - name: deployment-name
      value: "$deploy"
EOF
done

kubectl get pipelineruns -n ci-cd
```

### 5. Deploy via ArgoCD (GitOps)
```bash
kubectl apply -f argocd-apps/
kubectl get applications -n argocd
kubectl get pods -n apps
```

All three Applications should reach `Synced` / `Healthy`, with one Pod each Running in the `apps` namespace.

---

## Verifying the GitOps Loop

The cluster is never touched directly — only Git is. To prove it, scale `python-backend` purely via a commit:

```bash
# edit python-backend/deployment.yaml: replicas: 1 -> 2
git add python-backend/deployment.yaml
git commit -m "Scale python-backend to 2 replicas"
git push origin main

kubectl patch application python-backend -n argocd --type merge \
  -p '{"metadata":{"annotations":{"argocd.argoproj.io/refresh":"hard"}}}'

kubectl get pods -n apps -l app=python-backend --watch
```

A second `python-backend` Pod appears automatically within seconds — no `kubectl apply` or `kubectl scale` was run.

---

## Everyday Operations

```bash
# Status
kubectl get pipelineruns -n ci-cd
kubectl get pods -n ci-cd
kubectl get applications -n argocd
kubectl get pods -n apps

# Force an immediate sync
kubectl patch application <app-name> -n argocd --type merge \
  -p '{"metadata":{"annotations":{"argocd.argoproj.io/refresh":"hard"}}}'

# Logs, filtered by label
kubectl logs -n apps -l app=python-backend --tail=20
kubectl logs -n apps -l app=java-backend --tail=20
kubectl logs -n apps -l app=angular-frontend --tail=20

# Reach the apps
kubectl port-forward -n apps svc/python-backend 5000:5000 &
kubectl port-forward -n apps svc/java-backend 8080:8080 &
minikube service angular-frontend -n apps --url
```

---

## Known Issues & Fixes (see full report for details)

| Symptom | Cause | Fix |
|---|---|---|
| `unknown field "spec.serviceAccountName"` on PipelineRun | Tekton v1 moved the field | Use `spec.taskRunTemplate.serviceAccountName` |
| ArgoCD: `app path does not exist` | `Application.spec.source.path` didn't match actual repo layout | Point `path` at the app's real top-level directory |
| ArgoCD stuck `Synced` but no Pods | `deployment.yaml`/`service.yaml` were missing from the app directory | Add manifests, commit, push |
| Angular app stuck `Unknown`: `Failed to unmarshal "tsconfig.app.json"` | ArgoCD tried to parse every JSON/YAML file (incl. TS config) as a manifest | Restrict `spec.source.directory.include` to `{deployment.yaml,service.yaml}` |
| `DeadlineExceeded` generating manifest | Transient repo-server stall | `kubectl rollout restart deployment argocd-repo-server -n argocd` |

---

## Security Note

This setup is intended for local development and demonstration only: simple credentials, in-cluster image builds with no external registry, and no TLS. A production deployment would additionally require TLS termination, hardened RBAC, an external container registry, and a secrets-management solution.

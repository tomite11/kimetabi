---
name: kimetabi-preview-operations
description: Safely inspect, stop, and resume the Kimetabi Google Cloud preview environment, including Cloud Run, Cloud SQL, Cloud Scheduler, Cloud Tasks, and Monitoring alert policies. Use for preview shutdowns, restarts, cost-control pauses, and status checks; do not use for production or resource deletion beyond the preview Cloud Run service.
---

# Kimetabi Preview Operations

Operate only the approved preview environment. Read `AGENTS.md`,
`infrastructure/README.md`, and
[`references/stop-resume.md`](references/stop-resume.md) before changing cloud
state. Also load `$kimetabi-platform-security` for the repository's GCP and
least-privilege constraints.

## Guardrails

- Resolve the active gcloud account, project, region, and target resources
  before any mutation. The expected project is
  `kimetabi-preview-998740556155` in `asia-northeast1`; stop if reality or the
  repository configuration disagrees.
- Treat inspection as read-only. Obtain explicit user approval immediately
  before stop, delete, pause, resume, patch, or Terraform apply operations.
- Never disable deletion protection or delete Cloud SQL, backups, secrets,
  buckets, queues, Scheduler jobs, notification channels, or Terraform state.
- Cloud Run has no stopped state. For a full shutdown, delete only
  `kimetabi-preview-api`; Terraform recreates it during resume.
- Stop producers before consumers. Resume data services and consumers before
  periodic producers.
- Discover alert-policy IDs by display name on every run. Do not reuse IDs
  copied from an earlier session.
- Do not print secret payloads or place database credentials in committed
  files, command arguments, logs, or the final report.
- Manual shutdown creates intentional Terraform drift. Do not run an
  unreviewed `terraform apply` while the environment is stopped.

## Workflow

1. Inspect and report the current state using the reference procedure.
2. Classify the request as status, stop, or resume. Do not infer production
   scope from a generic request.
3. Show the exact resources and effects, then request approval for the
   mutation.
4. Execute the applicable procedure in order and stop on the first unexpected
   state or failed command.
5. Verify every postcondition independently. A successful command alone is not
   sufficient.
6. Report changed resources, retained data/protection, verification results,
   and Terraform drift or residual failures.

For resume, review the complete Terraform plan before applying it. Continue
only when changes are limited to the approved preview recovery and contain no
unexpected replacement, deletion, IAM expansion, secret change, or database
change. Keep Scheduler jobs paused until the API health check succeeds, then
resume the Tasks queue and Scheduler jobs and perform a final status check.

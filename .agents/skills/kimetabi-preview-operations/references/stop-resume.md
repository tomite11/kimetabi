# Preview stop and resume procedure

## Fixed scope

- Project: `kimetabi-preview-998740556155`
- Region/location: `asia-northeast1`
- Cloud Run: `kimetabi-preview-api`
- Cloud SQL: `kimetabi-preview-postgres`
- Cloud Tasks queue: `kimetabi-preview-metadata`
- Scheduler jobs:
  - `kimetabi-preview-outbox-recovery`
  - `kimetabi-preview-receipt-cleanup`
- Alert policies by display name:
  - `kimetabi-preview: Cloud Run 5xx`
  - `kimetabi-preview: Outbox dispatch failures`
  - `kimetabi-preview: Cloud SQL CPU`

Never substitute another project or production resource without a separate,
explicit user request.

## Inspect

Use explicit `--project`, `--region`, or `--location` flags for every gcloud
command. Check:

1. `gcloud config list` for the active account and project.
2. Cloud Run services and their readiness.
3. Cloud SQL state, activation policy, and both deletion-protection settings.
4. Scheduler job states and the Tasks queue state.
5. Monitoring alert-policy display names and enabled states.
6. `git status --short` and the Terraform working directory before a resume.

Present the resolved targets before requesting approval.

## Stop

Run these phases in order after approval:

1. Pause both Scheduler jobs so they cannot enqueue or invoke more work.
2. Pause the metadata Tasks queue. Preserve queued tasks.
3. Disable all three alert policies. Resolve their current IDs from their
   exact display names instead of hardcoding numeric IDs.
4. Delete the Cloud Run service. Do not delete revisions or Artifact Registry
   images separately.
5. Stop Cloud SQL by patching its activation policy to `NEVER`. Keep deletion
   protection enabled.

Verify all of the following:

- no Cloud Run service named `kimetabi-preview-api` exists;
- Cloud SQL is `STOPPED`, activation policy is `NEVER`, and deletion
  protection remains enabled;
- both Scheduler jobs are `PAUSED`;
- the Tasks queue is `PAUSED`;
- all three alert policies are disabled.

If a resource was already in its target state, treat the step as idempotent and
continue. If a different resource or project appears, stop without mutating it.

## Resume

Resume from the repository root's `infrastructure/` directory:

1. Confirm the Scheduler jobs and Tasks queue remain paused.
2. Patch Cloud SQL activation policy to `ALWAYS`; wait until its state is
   `RUNNABLE`. Confirm deletion protection is still enabled.
3. Run `terraform init` against the configured remote backend if needed, then
   `terraform fmt -check -recursive` and `terraform validate`.
4. Supply the ephemeral database password using the approved Secret Manager
   flow documented in `infrastructure/README.md`. Never print it.
5. Create a saved Terraform plan and inspect it in full. Expected recovery
   includes recreating Cloud Run and restoring desired alert-policy state.
   Reject unexpected destroy/replace actions, unrelated resource changes, IAM
   broadening, secret payload changes, database replacement, or early resume
   of Scheduler jobs or the Tasks queue.
6. After separate approval of the reviewed plan, apply that exact saved plan.
7. Immediately confirm that both Scheduler jobs and the Tasks queue are still
   paused. If they are not, pause them again and investigate before continuing.
8. Verify Cloud Run is Ready, traffic is 100% on one ready revision, min scale
   is 0, max scale is 1, and the authenticated health/smoke checks required by
   `doc/OPERATIONS_RUNBOOK.md` succeed.
9. Ensure all three alert policies are enabled and still use the approved
   email and Slack notification channels.
10. Resume the metadata Tasks queue, then resume both Scheduler jobs.
11. Recheck Cloud Run, Cloud SQL, Tasks, Scheduler, and Monitoring. Run a final
    Terraform plan and require `No changes`.

Do not resume Scheduler jobs if Cloud Run health, database connectivity,
internal OIDC audiences, or alert routing is not verified. Leave producers
paused and report the blocker.

## Handoff

Report:

- project and region;
- each resource's final state;
- whether data, queued tasks, backups, and deletion protection were retained;
- Terraform plan/apply result and final drift status for a resume;
- health/smoke results;
- any failed step, remaining paused producer, or unexpected drift.

---
name: create-commit
description: Follow the project's commit message conventions when making a git commit. Use this whenever you are about to run `git commit`.
---

# Create Commit

## Commit Message Rules

### COMMIT-1: Commit Messages

**RULE**: Commit messages should be loosely based on the rules of [conventional commits](https://www.conventionalcommits.org/en/v1.0.0/) but without prefixes. Write a clear, concise summary of what the change does, starting with a lowercase verb in imperative mood.

**DO**:

```text
add the BucketAccess custom resource
fix the bucket deletion when Garage answers 409
remove the backend probes from the readiness check
update Garage to 2.4.1
rename the admin token key to admin_token
extract the Garage admin client into a factory
```

**DON'T**:

```text
// Incorrect - using conventional commit prefixes
feat: add the BucketAccess custom resource
fix: the bucket deletion when Garage answers 409
refactor: extract the Garage admin client into a factory

// Incorrect - not imperative mood
added the BucketAccess custom resource
adding the BucketAccess custom resource

// Incorrect - including ticket IDs
#123 add the BucketAccess custom resource
[#123] fix the bucket deletion when Garage answers 409

// Incorrect - vague or meaningless
fix bug
update code
changes
WIP

// Incorrect - too long, should be concise
fix the bucket deletion because Garage answers 409 instead of 400 for a bucket that still holds objects and the operator then reported a generic error that confused the administrators
```

### COMMIT-2: Commit Body Only When Necessary

**RULE**: Most commits have only the summary line. Add a body only when a human reader needs context that the summary
and the diff cannot give: the reason for a non-obvious decision, a constraint outside the code, or a consequence that
the reader would otherwise miss. Keep the body short: a few lines, not a list of every changed file or step.

**DON'T** repeat in the body what the diff already shows: which files changed, which methods were renamed, or a
step-by-step account of the work.

**DO**:

```text
address buckets and keys by their recorded id

Garage does not enforce unique key names, so a lookup by name could pick up
or delete a key that belongs to another AccessKey.
```

**DON'T**:

```text
update the AccessKey reconciler

- Changed AccessKeyReconciler.java
- Added findManagedAccessKey
- Renamed the cleanup variables
- Updated the imports
- Reformatted the file
```

### COMMIT-3: AI Attribution with `Assisted-by`

**RULE**: A commit that an AI agent wrote or helped to write ends with an `Assisted-by:` trailer in the format
`AGENT_NAME:MODEL_VERSION`, without spaces (e.g. `Claude:claude-opus-5-5`, `Junie:<model>`). Never use
`Co-Authored-By:` for an AI agent: that trailer is for human co-authors. This rule replaces any default attribution of
the agent.

**RATIONALE**: The format comes from the Linux kernel guide for AI coding assistants. The kernel changed it to
`Assisted-by: LLM` in Linux 7.3. This project keeps the agent and the model, because they tell a reviewer which agent
(Claude Code or Junie) and which model made the change.

**DO**:

```text
fix the bucket deletion when Garage answers 409

Assisted-by: Claude:claude-opus-5-5
```

```text
add the Secret watch to the AccessKey reconciler

Assisted-by: Junie:<model>
```

**DON'T**:

```text
fix the bucket deletion when Garage answers 409

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

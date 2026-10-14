# AboutBits Garage Operator - Agent Guidelines

This is the primary guide for AI agents and developers. It provides project context, operational commands, and links to
the skills. The skills live in [`.agents/`](./.agents).

## Contain Changes — Scope, Not Diff

When asked to fix or change one thing, keep the **scope** to that thing: do not refactor code the
task does not touch unless you flag it first and the user agrees. If a task is getting complex and
you're about to make many interconnected changes, pause and summarize the plan before executing.

Inside that scope, the size of the diff is not a criterion. Code the task does touch takes the shape
it should have, whether it existed before or not. Never build *around* existing code to avoid
changing it — a wrapper, a flag, a second copy of a class somewhere else — because it means
"fewer changes". Each such layer is cheap on the day and permanent afterwards.

## Greenfield Within a PR

**The code a PR introduces is greenfield: it can and should be amended while the PR is still open.**
We are after a good solution, not a preserved history. If something written an hour ago turns out to
need a different shape, reshape it — there is nothing to "salvage" from an unmerged PR, and no need
to layer a fix on top of code that only exists on this branch. A reviewer reads the final state.

This is about the PR's **own** new code. Code the PR merely touches is not greenfield: it follows
[Contain Changes](#contain-changes--scope-not-diff) above.

## 🎯 Project Intent (The "Why")

The **Garage Operator** manages S3 object storage on Kubernetes declaratively. It is the companion of the
[AboutBits Garage Helm chart](https://github.com/aboutbits/helm-garage): the chart runs Garage, the operator assigns its
cluster layout and manages buckets, access keys and their permissions as Custom Resources.

It follows the structure of the sibling [AboutBits PostgreSQL Operator](https://github.com/aboutbits/postgresql-operator).
When in doubt about a pattern, look there first.

## 🚀 Project Overview

- **Stack**: Java 25, Quarkus, the Quarkus Operator SDK and the fabric8 Kubernetes client.
- **Generated artifacts**: the CRDs and the Helm chart are generated from the code at build time, into
  `operator/build/kubernetes` and `operator/build/helm`. Change the Java classes or `application.yml`, never the output.
- **Backend**: Garage only, driven through its Admin API v2. `GarageService` in `core/adminapi` is what the
  reconcilers call; `GarageAdminApi` is the HTTP client underneath. The exception is `GarageClusterReconciler`: it
  builds the client from its own `adminEndpoint` and passes it to `GarageClusterService`.
- **Backend identity**: once a bucket or key is created or adopted, its id is recorded in the status and it is
  addressed by that id — Garage key names are not unique. The name is used again only when that id is gone from the
  backend: the bucket or key is then adopted by name, or created anew.
- **Tests**: `@QuarkusTest` integration tests against a K3s cluster and a real Garage node, both provided by Quarkus
  Dev Services, so Docker is required. Prefer a test against the real Garage over a mock: what matters is how the
  Admin API actually behaves.

## 📐 Conventions

- **Code style**: the shared AboutBits Checkstyle config, and JSpecify `@NullMarked` checked by NullAway.
- **Message keys**: in a log or an error message, the key in the brackets is the path of the field in the CRD for a
  value from the CRD, for example `spec.name` and `status.phase`. For any other value, it is the name of the variable
  or of the method, in camelCase, for example `bucketId`. A reference to one object is `key=namespace/name`, for example
  `resource=%s/%s` and `secret=%s/%s`.
- **Comments**: comment only what the code cannot say — a trap, a quirk of the backend, a contract. Do not restate the
  code. Keep the density of the PostgreSQL operator.
- **Test structure**: mark the sections of a test with the comments `// given`, `// when` and `// then`. Add
  `// precondition` for a check between the setup and the call, with a reason when it is necessary.
- **Docs**: a change in behavior updates the matching page in `docs/`, and the `README.md` if an administrator needs
  to know about it.
- **Namespace and name**: `namespace` always comes before `name`, in YAML, in code lists and in comments.

## 🛠️ Operational Commands

The [`Makefile`](./Makefile) holds the common commands:

- **Build**: `make install`
- **Run in dev mode**, against a throwaway K3s cluster and Garage node: `make run`
- **Run the tests**: `make test`
- **Run Checkstyle**: `./gradlew checkstyleMain checkstyleTest`. It needs GitHub Packages credentials, see the
  [`README.md`](./README.md).
- **Lint Markdown**: `make lint`

## 🧩 Automation Skills

Before performing common tasks, check if a specialized skill exists in [`.agents/skills/`](./.agents/skills):

| Skill           | Use when...         |
|-----------------|---------------------|
| `create-commit` | Making a git commit |

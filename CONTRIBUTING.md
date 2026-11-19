# Contributing to Loop

Thanks for your interest in Loop. It is a solo project for now, built in the open.

## What is welcome today

GitHub issues are the place for anyone outside the project. The files in `.loop/issues/` are the maintainer's own backlog, which Loop works.

- **Bug reports.** Open a GitHub issue with what you ran, what you expected and what happened. `loop trace <issue>` and `loop show <attempt>` output helps a lot. Check that it holds no secret before you paste it.
- **Ideas and questions.** Open a GitHub issue. Say what you are trying to do, not only the feature you want.

## Pull requests

Outside pull requests are not accepted yet. Loop's design moves quickly and its own backlog is worked by Loop, so a pull request from outside would most likely conflict with work already planned. This will change once the design settles. Until then, an issue describing the change is the best way to propose it.

## Security

Do not open a public issue for a security problem. See [`SECURITY.md`](SECURITY.md).

## When pull requests open

Contributions will be accepted under the [Developer Certificate of Origin](https://developercertificate.org/) (DCO). Each commit must carry a sign off line, added with `git commit -s`:

```
Signed-off-by: Your Name <you@example.com>
```

It certifies that you wrote the change, or have the right to submit it, under the project's license, Apache 2.0. By contributing, you agree that your contribution is licensed under the [Apache License 2.0](LICENSE).

## How the project works

- Issues the maintainer and Loop work on live as files in [`.loop/issues/`](.loop/issues/), described in [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md).
- Terms are defined in [`CONTEXT.md`](CONTEXT.md). Decisions are in [`docs/adr/`](docs/adr/) and [`docs/spec-decisions.md`](docs/spec-decisions.md).
- Writing rules for code comments, docs and commit messages: sentence case, and no em or en dashes.
- `./gradlew build` must pass. It is the check CI runs.

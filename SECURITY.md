# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately through GitHub's private vulnerability reporting: the **Security** tab of this repository, then **Report a vulnerability**.

Include what you found, how to reproduce it, and the impact you expect. You will get an answer as soon as possible. Loop is maintained by one person, so there is no fixed response time.

## Scope

Loop runs coding agents unattended on your machine. These are known limits, not vulnerabilities:

- Agents run with their permission prompts off and without an operating system sandbox, and can run anything your user can.
- Loop removes tracker credentials from the agent's environment and blocks some commands, but this is a guard rail, not isolation.

A way for an agent, an issue or anyone outside the project to get past Loop's guards is in scope: reading a secret Loop hides, writing to the tracker without Loop's tools, delivering a result that was not verified, or getting text into an agent's prompt that only maintainers should be able to write.

## Supported versions

Loop is pre 1.0. Only the latest commit on `main` is supported.

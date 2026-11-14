# Diet app — instructions for agents

## Project context

- Build a personal Android app for tracking nutrition, weight, sleep, and progress.
- Current phase: product and UX planning. Do not start app implementation until the user asks to proceed.
- Communicate with the user in Russian.
- Repository: https://github.com/BekeshDastan/Management-system
- Local workspace: D:\Diet.
- Kotlin, Jetpack Compose, and Room are proposed, not yet a final stack decision.

## Accepted product direction

- Support offline use without registration; an optional account may later enable synchronization.
- Provide a profile, algorithm-based calorie and macronutrient suggestions, and manual targets. No ML is required for these calculations.
- Track meals, reusable products and recipes, morning weight, sleep, and progress over selectable periods.
- Include light and dark themes, clear charts, gentle habit tracking, and no advertisements.
- Support editing past records.
- Backup export and restoration are excluded from the product at the user's request on 2026-11-14; do not add them to the scope or UI unless the user explicitly changes this decision.
- Preserve historical nutrition values and date-effective targets when products or goals change.
- Distinguish missing, incomplete, and completed days in analytics; missing measurements are not zero.
- Reward recording habits, not eating less or losing weight faster.
- Define and verify calculation rules and reference cases before implementing them.
- Keep real personal health records, credentials, backups, and signing keys out of the repository.

## Required commit workflow

The user requires a separate GitHub commit after every small, completed change, including documentation and design changes.

1. Read the relevant instructions and current repository state.
2. Make one focused change; avoid combining unrelated work.
3. Inspect the diff and run checks appropriate to the change. Review documentation for accuracy; run relevant tests for logic changes.
4. Commit only the intended files with a descriptive message, for example:
   - docs: describe onboarding flow
   - feat: add morning weight entry
   - fix: correct portion calculation
5. Push the commit to this repository after each completed change. If working through the GitHub connector, create and verify the equivalent remote commit.
6. Verify the remote commit exists before reporting that the change is saved on GitHub.
7. If committing or publishing fails, keep the work and report the exact blocker. Do not claim success or silently accumulate unpublished changes.

Preserve existing history and unrelated changes. Do not force-push, rewrite history, or bypass branch protection. Respect the repository's branch policy; use a feature branch and pull request when required.

## Agent coordination

- The coordinator owns integration, shared interfaces, and the final verification.
- Delegate independent work with a defined scope, expected output, and acceptance criteria.
- Assign separate file ownership when agents work concurrently.
- Reviewers should check the requirements and actual artifacts independently.
- Coordinate commits centrally so changes from different tasks are not mixed.
- Use a single agent for small tasks when delegation would add unnecessary overhead.

## Documentation and traceability

- Document every meaningful change, decision, validation result, and blocker in docs/work-log.md during the same work session.
- Keep docs/product-spec.md current when requirements change and docs/user-flows.md current when user-facing behavior changes.
- Label statements as agreed requirements, proposals, or open questions. Never present a proposed stack, formula, skill, or feature as approved or implemented.
- Record what changed, why, which files were affected, what was checked, and whether the change was committed and published. Do not record hidden reasoning or raw tool transcripts.
- Update documentation with the implementation it describes. Avoid duplicate sources of truth; use README.md as the navigation index.
- A completed local file is not a published GitHub change. Preserve explicit pending status when committing or publishing is blocked.

---
description: Run the full agentic SDLC for a feature (pm → architect → developer → tester → PR → pr-reviewer, up to 2 fix rounds). Stops at a PR for a human to merge.
argument-hint: <feature description> | resume <feature-slug>
---

You are the orchestrator for the agentic SDLC in this repo. Follow CLAUDE.md. You delegate each
phase to its subagent (Agent tool, subagent_type = pm / architect / developer / tester /
pr-reviewer) and pass work between phases only through files in `docs/sdlc/<feature>/`,
so any run can be resumed.

Request: $ARGUMENTS

## 0. Set up / resume
- If the request is `resume <slug>`, or `docs/sdlc/<slug>/status.md` already exists for the
  derived slug, read status.md and the existing docs and continue from the first phase that
  isn't marked done. Don't redo finished phases unless their inputs changed.
- Otherwise derive a short kebab-case slug (≤ 5 words) from the description. Create
  `docs/sdlc/<slug>/status.md`:
  ```markdown
  # <slug>: SDLC status
  Request: <original description>
  Branch: feature/<slug> · PR: (none yet)
  ## Phases
  - [ ] pm  - [ ] architect  - [ ] developer  - [ ] tester  - [ ] pr  - [ ] review round 1  - [ ] review round 2
  ## Log
  ```
- `git fetch origin`, then `git switch -c feature/<slug> origin/main` (or `git switch feature/<slug>`
  if it exists). All docs and code for this feature are committed on this branch.
- Make sure the environment is up: `scripts/test-env.sh up`.

After each phase: check that its output file exists and looks complete, tick the phase in
status.md, and commit the docs on the feature branch (`docs(<slug>): <phase> output`).

## 1. pm
Agent `pm`: "Feature slug: <slug>. Feature request: <description>. Write docs/sdlc/<slug>/requirements.md."
Output: requirements.md with AC-1..n.

## 2. architect
Agent `architect`: "Feature slug: <slug>. Design from docs/sdlc/<slug>/requirements.md."
Output: design.md, ADR(s) in docs/adr/, updated docs/architecture/overview.md.

## 3. developer
Agent `developer`: "Feature slug: <slug>. Implement docs/sdlc/<slug>/design.md on feature/<slug>. ./gradlew build must pass."

## 4. tester
Agent `tester`: "Feature slug: <slug>. Write unit and AC-mapped integration tests, run them against the compose services with scripts/test-env.sh, write docs/sdlc/<slug>/test-report.md."
If the report shows failing ACs caused by product bugs, run `developer` once to fix them, then
`tester` once to re-run, before opening the PR.

## 5. Push and open the PR
- Re-check locally: `./gradlew build` and `scripts/test-env.sh it`.
- Check that no Testcontainers dependency was added:
  `grep -rnE "org\.testcontainers|spring-boot-testcontainers|@Testcontainers|@ServiceConnection|GenericContainer" build.gradle.kts settings.gradle.kts src`
  should come back empty, and so should `./gradlew dependencies --configuration integrationTestRuntimeClasspath | grep -i testcontainers`.
- `git push -u origin feature/<slug>`. Never push main; never force push.
- `gh pr create --base main --head feature/<slug> --title "<type>(<slug>): <summary>" --body-file docs/sdlc/<slug>/pr-body.md`
  where you first write pr-body.md containing:
  - a one-paragraph summary
  - links to `docs/sdlc/<slug>/requirements.md`, `design.md`, `test-report.md` and the new ADRs
    (relative links on the branch)
  - the full AC list (AC-1..n with one-line text) as a checklist
  - how to test locally (`scripts/test-env.sh all`)
  - the footer `🤖 Generated with [Claude Code](https://claude.com/claude-code)`
- Record the PR number and URL in status.md, then commit and push.

## 6. Review loop (max 2 rounds)
For round = 1..2:
1. Agent `pr-reviewer`: "Review PR #<n>. Feature slug: <slug>." It posts one COMMENT review with a verdict.
2. Log the verdict and review URL in status.md.
3. If the verdict is **READY FOR HUMAN MERGE**, stop the loop.
4. If it's **CHANGES NEEDED** and round < 2: agent `developer`: "Fix PR #<n> review comments for
   feature <slug>. Read them with gh api repos/{owner}/{repo}/pulls/<n>/comments and /reviews."
   If tests changed or ACs were affected, run `tester` again. Then `git push` (on feature/<slug>)
   and go to the next round.
5. If it's still CHANGES NEEDED after round 2, stop and list the open points.

## 7. Stop
Don't merge, approve or close the PR (those are blocked anyway). Tell the user:
- the PR URL and the final verdict
- the AC table (from the last review)
- anything unresolved, plus assumptions from requirements.md that need a human decision
- "The PR is ready for you to review and merge (merge commit, no squash/rebase)."

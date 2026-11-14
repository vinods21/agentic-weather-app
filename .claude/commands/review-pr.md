---
description: Run only the pr-reviewer on a GitHub PR (agent- or human-authored). Posts one COMMENT review with an AC table and verdict.
argument-hint: <pr-number>
---

Review pull request #$ARGUMENTS.

1. Check that `$ARGUMENTS` is a PR number (`gh pr view $ARGUMENTS --json number,title,headRefName,state`).
   If it isn't, or the PR is closed, say so and stop.
2. Delegate to the `pr-reviewer` agent (Agent tool, subagent_type `pr-reviewer`) with:
   "Review PR #$ARGUMENTS. Derive the feature slug from the head branch and read docs/sdlc/<slug>/ if it exists."
3. Do not edit code, push, approve, merge or close anything.
4. Report back: the verdict, the review URL, the AC traceability table, and the blocker/major findings.

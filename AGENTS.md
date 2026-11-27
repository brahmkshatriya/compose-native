# Agent Guide: Syncing Compose Multiplatform Upstream

This repository is a pruned Compose Multiplatform checkout with flattened local history. Integrate
upstream changes as incremental patches. Do not make JetBrains upstream commits ancestors of
`main`.

The GitHub repository cannot accept the complete upstream history because that history contains
files larger than GitHub's 100 MB limit. In particular,
`camera/gradle/wrapper/gradle-4.6-all.zip` is 101.78 MB. A normal merge may work locally but its push
will be rejected by GitHub.

## Repository and branch

- Local repository: `compose-native`
- Local integration branch: `main`
- Push remote: `origin`
- Upstream repository: `https://github.com/JetBrains/compose-multiplatform-core.git`
- Upstream default branch: `jb-main`
- Configured upstream remote: `upstream`

Do not perform this operation in the sibling `skiko-native` checkout.

## Upstream release policy

Sync to the newest published JetBrains Compose Multiplatform release. Stable, RC, beta, and
alpha releases are eligible. Check the
[Compose Multiplatform release list](https://github.com/JetBrains/compose-multiplatform/releases)
and use the matching version tag from `compose-multiplatform-core` as the source.

Compare the numeric version first. For the same version, precedence from highest to lowest is
stable, RC, beta, then alpha. Within each prerelease stage, compare the sequence number numerically. Skip
development tags such as `+dev` builds, snapshots, and branch tips beyond the release tag.
The AndroidX version merged into `jb-main` does not establish a Compose Multiplatform release.

## Current synchronization point

- Flattened local snapshot base: `c1f04f0b9b7acda3849d76fe0d271f7255ad827c`
- Upstream release tag: `v1.13.0-rc01`
- Last integrated upstream tip: `f39822e6a77eea5d194e4f9bb56cb6961321b849`
- Local single-parent integration commit: `f71201e9b382d99d554e6d8c031973bf205def6f`

Record the release tag, the commit it points to, and the local integration commit after each sync.

## Normal procedure for future upstream updates

Start with a clean understanding of the worktree and preserve user changes:

```bash
git status --short --branch
git remote -v

# Add this only if the remote is missing.
git remote add upstream https://github.com/JetBrains/compose-multiplatform-core.git

# Select the version from the published release list above.
read -r -p 'Verified Compose Multiplatform release tag: ' agent_release_tag
git fetch --no-tags upstream \
  "refs/tags/$agent_release_tag:refs/remotes/upstream/releases/$agent_release_tag"
agent_previous_upstream=f39822e6a77eea5d194e4f9bb56cb6961321b849
agent_new_upstream=$(git rev-parse "refs/remotes/upstream/releases/$agent_release_tag^{commit}")

# Check whether the last sync point and release are on the same line of history.
git merge-base --is-ancestor "$agent_previous_upstream" "$agent_new_upstream"
```

If the ancestry check reports divergence, inspect both sides before applying the patch. Release
branches can contain fixes cherry-picked from the development branch. Check which changes from the
previous upstream sync are absent from the release so the patch brings the upstream baseline to the
chosen tag while preserving fork edits.

```bash
git log --left-right --cherry-pick --oneline "$agent_previous_upstream...$agent_new_upstream"
```

```bash
agent_saved_worktree=false
if test -n "$(git status --porcelain)"; then
  git stash push --include-untracked -m "agent: preserve worktree before upstream patch sync"
  agent_saved_worktree=true
fi
```

Generate and apply only the incremental upstream delta. Keep the patch outside the repository:

```bash
agent_upstream_patch=$(mktemp)
git diff --binary --full-index \
  "$agent_previous_upstream" "$agent_new_upstream" -- > "$agent_upstream_patch"

git apply --3way --index "$agent_upstream_patch"
```

Review the staged result carefully. A modified upstream file that was intentionally removed from
this pruned checkout can produce a conflict; resolve it explicitly instead of importing unrelated
AndroidX trees. New files added by upstream during the selected interval are normally expected.

Before committing:

1. Update the synchronization SHAs in this file.
2. Format every modified Kotlin file with `:ktCheckFile --format` as required by
   `compose/AGENTS.md`.
3. Run the most relevant build or test tasks.
4. Check `git diff --cached --check` and inspect `git diff --cached --stat`.

Create a normal single-parent commit, for example:

```bash
git add AGENTS.md
git commit -m "Sync upstream ${agent_release_tag} through ${agent_new_upstream:0:12}"
```

Restore saved work only after the integration commit succeeds:

```bash
if test "$agent_saved_worktree" = true; then
  git stash pop stash@{0}
fi
```

Remove the temporary patch after it is no longer needed. Do not push unless the user asks for it.

## Required ancestry checks

Before pushing an upstream synchronization, verify that the upstream release commit is not an ancestor of
local `main`. A nonzero result from the first command is expected:

```bash
if git merge-base --is-ancestor "$agent_new_upstream" HEAD; then
  echo "ERROR: main contains unpushable upstream ancestry"
  exit 1
fi

git ls-files -u                  # Must print nothing.
git replace -l                  # Must print nothing.
git status --short --branch
```

Never use any of these on `main`:

- Merging an upstream branch or release tag directly into `main`
- `git pull upstream jb-main`
- resetting `main` to an upstream commit
- pushing the local backup branch that contains the original upstream merge

Cherry-picking or applying an upstream change is acceptable because it creates a new local commit
without retaining the upstream commit as a parent.

## Initial synchronization history

On 2026-08-09 the local root was verified as a pruned copy of upstream snapshot `c1f04f0`. A
temporary graft allowed Git to calculate a correct three-way merge through upstream tip `cfa2ff1`.
That local merge produced the intended 25-file delta, but GitHub rejected its ancestry first at the
shallow boundary and then because of the 101.78 MB historical Gradle ZIP.

The verified merge delta was therefore recorded as single-parent commit `b418ec78099`, preserving
the merged file contents without retaining the unpushable upstream history. The original merge is
kept locally on `backup/unpushable-upstream-merge-20260809` for recovery and must not be pushed.

## Recovery notes

- If `git apply --3way --index` conflicts, inspect `git status` and resolve only the intended
  upstream paths. Use `git reset` without `--hard` to unstage an abandoned application.
- A successful `git stash pop` drops that stash automatically. If it conflicts, the stash is
  normally retained; confirm with `git stash list` before taking further action.
- Never use `git reset --hard`, `git clean -fd`, or checkout-based file restoration to handle the
  user's dirty worktree.

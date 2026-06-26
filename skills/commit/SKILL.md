---
name: commit
description: Generate a Conventional Commits message from the current git diff. Use when asked to create a commit message, commit changes, or write a commit.
allowed_tools: [ReadFile, Grep, ExecuteCommand]
mode: inline
---

# Commit Message Skill

You help the user write a clear, conventional commit message.

## Steps

1. **Check the status**: Run `git status` to see which files are changed and staged.
2. **Get the diff**: Run `git diff --staged` (or `git diff` if nothing is staged).
3. **Analyze the changes**: Understand what the diff does — new feature, bug fix, refactor, docs, etc.
4. **Determine the type**:
   - `feat`: New feature
   - `fix`: Bug fix
   - `docs`: Documentation only
   - `style`: Formatting, whitespace (no code change)
   - `refactor`: Code restructuring without behavior change
   - `perf`: Performance improvement
   - `test`: Adding or fixing tests
   - `chore`: Build process, dependencies, tooling
5. **Determine the scope** (optional but recommended): a short noun describing what part of the codebase (e.g., `feat(auth):` or `fix(ui):`)

## Output Format

Present the commit message formatted for direct use:

```
<type>(<scope>): <short description>

<body: optional multi-line explanation of WHAT and WHY>
```

**Rules:**
- Subject line: ≤ 72 characters, imperative mood ("add" not "added"), no period at end
- Body: wrap at 72 characters, explain the motivation behind the change
- If there are breaking changes, add `BREAKING CHANGE:` in the body

If the user wants, offer to execute `git commit -m "..."` after they approve the message.

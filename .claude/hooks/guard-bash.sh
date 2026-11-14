#!/usr/bin/env bash
# PreToolUse guard for the Bash and PowerShell tools.
#
# Reads the hook JSON from stdin, inspects tool_input.command and exits 2 (block,
# reason on stderr) for destructive or outward-facing commands. It runs before the
# permission rules, so it also blocks commands that an allow rule would let through.
# Every chained sub-command (&&, ||, ;, |, &, newlines, $(...), `...`, subshells,
# bash -c "...") is checked on its own.
#
# Exit 0 = no objection (normal permission rules still apply). Exit 2 = block.
# Run .claude/hooks/test-guard.sh after editing this file.

set -u

block() {
  printf 'BLOCKED by .claude/hooks/guard-bash.sh: %s\n' "$*" >&2
  printf 'This project forbids it (see CLAUDE.md). If a human really needs it, they run it themselves.\n' >&2
  exit 2
}

input="$(cat)"

# ---------------------------------------------------------------- parse input
parse_with_python() {
  local py
  for py in python3 python; do
    if command -v "$py" >/dev/null 2>&1 && "$py" -c 'import sys' >/dev/null 2>&1; then
      printf '%s' "$input" | "$py" -c '
import sys, json
d = json.loads(sys.stdin.buffer.read().decode("utf-8"))
cwd = d.get("cwd") or ""
cmd = (d.get("tool_input") or {}).get("command") or ""
sys.stdout.buffer.write((cwd + "\n" + cmd).encode("utf-8"))
'
      return $?
    fi
  done
  return 1
}

if command -v jq >/dev/null 2>&1; then
  parsed="$(printf '%s' "$input" | jq -j '(.cwd // "") + "\n" + (.tool_input.command // "")')" ||
    block "could not parse hook input with jq"
else
  parsed="$(parse_with_python)" || block "could not parse hook input (install jq or python3)"
fi

hook_cwd="${parsed%%$'\n'*}"
cmd="${parsed#*$'\n'}"
[[ -z "$hook_cwd" ]] && hook_cwd="$PWD"
[[ -z "${cmd//[[:space:]]/}" ]] && exit 0

# ------------------------------------------------------ whole-command checks
# Never approve a PR through the API, whatever the transport (-f, JSON, heredoc).
re_approve='event[\"'\'']?[[:space:]]*[:=][[:space:]]*[\"'\'']?approve'
[[ "${cmd,,}" =~ $re_approve ]] && block 'PR reviews may only use event COMMENT, never APPROVE (humans approve and merge)'

# Piping anything into a shell interpreter.
re_pipe_sh='\|[[:space:]]*(sudo[[:space:]]+)?([^[:space:]|]*/)?(ba|z|da|k|fi)?sh([[:space:]]|$)'
[[ "$cmd" =~ $re_pipe_sh ]] && block 'piping into sh/bash is not allowed'
re_pipe_iex='\|[[:space:]]*(iex|invoke-expression)([[:space:]]|$)'
[[ "${cmd,,}" =~ $re_pipe_iex ]] && block 'piping into Invoke-Expression is not allowed'

# ------------------------------------------------------------ helpers
# Remove heredoc bodies fed to ordinary commands (commit messages, review JSON)
# so prose inside them isn't mistaken for commands. Heredocs fed to a shell are kept.
strip_heredocs() {
  local out="" delim="" line trimmed
  local re_start='(^|[^<])<<-?[[:space:]]*[\"'\'']?([A-Za-z_][A-Za-z0-9_]*)'
  local re_shell='(^|[[:space:];&|(])(sudo[[:space:]]+)?(ba|z|da|k)?sh([[:space:]]+-[a-z]+)*[[:space:]]*<<'
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ -n "$delim" ]]; then
      trimmed="${line#"${line%%[![:space:]]*}"}"
      [[ "$line" == "$delim" || "$trimmed" == "$delim" ]] && delim=""
      continue
    fi
    out+="$line"$'\n'
    if [[ "$line" =~ $re_start ]] && ! [[ "$line" =~ $re_shell ]]; then
      delim="${BASH_REMATCH[2]}"
    fi
  done <<< "$1"
  printf '%s' "$out"
}

is_secret_path() {
  local p="${1//\\//}" base
  p="${p#\~}"
  base="${p##*/}"
  case "$base" in
    .env.example|.env.sample|.env.template|.env.dist) return 1 ;;
  esac
  [[ "$base" == ".env" || "$base" == .env.* ]] && return 0
  [[ "$base" == id_rsa* || "$base" == id_ed25519* || "$base" == id_ecdsa* ]] && return 0
  [[ "$p" =~ (^|/)(secrets|\.ssh|\.aws|\.kube)(/|$) ]] && return 0
  return 1
}

current_branch() { git -C "$1" symbolic-ref --short -q HEAD 2>/dev/null; }

is_protected_ref() {
  local r="${1#refs/heads/}"
  [[ "$r" == "main" || "$r" == "master" ]]
}

# ------------------------------------------------------------ git
check_git_push() {
  local dir="$1"; shift
  local -a args=("$@") pos=()
  local n=${#args[@]} j x
  for ((j = 0; j < n; j++)); do
    x="${args[j]}"
    case "$x" in
      --force|--force=*|--force-with-lease|--force-with-lease=*|--force-if-includes)
        block "force push ($x) is not allowed" ;;
      --delete|--prune) block "deleting remote branches ($x) is not allowed" ;;
      --mirror|--all|--branches) block "git push $x would also push main/master" ;;
      -o|--push-option|--repo|--receive-pack|--exec) ((j++)) ;;
      --) pos+=("${args[@]:j+1}"); break ;;
      --*) ;;
      -*)
        [[ "$x" =~ ^-[a-zA-Z]*f ]] && block "force push ($x) is not allowed"
        [[ "$x" =~ ^-[a-zA-Z]*d ]] && block "deleting remote branches ($x) is not allowed"
        ;;
      *) pos+=("$x") ;;
    esac
  done

  local branch; branch="$(current_branch "$dir")"
  if ((${#pos[@]} <= 1)); then
    # Bare `git push` / `git push origin`: the target is the current branch (or its push target).
    is_protected_ref "$branch" && block "git push while on '$branch' would push to $branch; push a feature/*, fix/* or chore/* branch"
    local target; target="$(git -C "$dir" rev-parse --abbrev-ref '@{push}' 2>/dev/null)"
    [[ -n "$target" ]] && is_protected_ref "${target#*/}" && block "the push target of '$branch' is $target"
    return 0
  fi

  local r src dst
  for r in "${pos[@]:1}"; do
    [[ "$r" == +* ]] && block "force push via +refspec ($r) is not allowed"
    if [[ "$r" == *:* ]]; then
      src="${r%%:*}"; dst="${r#*:}"
      [[ -z "$src" ]] && block "deleting a remote branch via ':$dst' is not allowed"
    else
      src="$r"; dst="$r"
    fi
    [[ "$dst" == "HEAD" || "$dst" == "@" ]] && dst="$branch"
    is_protected_ref "$dst" && block "pushing to ${dst#refs/heads/} is not allowed; humans merge PRs into main"
  done
  return 0
}

check_git() {
  local -a a=("$@")
  local i=0 dir="$hook_cwd" n=${#a[@]}
  while ((i < n)); do
    case "${a[i]}" in
      -C) dir="${a[i+1]:-.}"; [[ "$dir" != /* && "$dir" != [A-Za-z]:* ]] && dir="$hook_cwd/$dir"; ((i += 2)) ;;
      -c|--git-dir|--work-tree|--namespace|--exec-path) ((i += 2)) ;;
      -*) ((i++)) ;;
      *) break ;;
    esac
  done
  local sub="${a[i]:-}"
  local -a rest=("${a[@]:i+1}")
  local x
  case "$sub" in
    push) check_git_push "$dir" "${rest[@]}" ;;
    rebase) block 'git rebase is not allowed (merge commits only: git merge --no-ff / git pull --no-rebase)' ;;
    pull)
      for x in "${rest[@]}"; do
        [[ "$x" == "-r" || "$x" == "--rebase" || "$x" =~ ^--rebase=(true|merges|interactive|i)$ ]] &&
          block 'git pull --rebase is not allowed; use git pull --no-rebase'
      done ;;
    reset)
      for x in "${rest[@]}"; do [[ "$x" == "--hard" ]] && block 'git reset --hard is not allowed'; done ;;
    branch)
      local del=0 force=0
      for x in "${rest[@]}"; do
        [[ "$x" =~ ^-[a-zA-Z]*D ]] && block 'git branch -D is not allowed (use -d for merged branches)'
        [[ "$x" == "--delete" || "$x" =~ ^-[a-zA-Z]*d ]] && del=1
        [[ "$x" == "--force" || "$x" =~ ^-[a-zA-Z]*f ]] && force=1
      done
      ((del && force)) && block 'force-deleting a branch is not allowed' ;;
    clean)
      for x in "${rest[@]}"; do
        [[ "$x" == "--force" || "$x" =~ ^-[a-zA-Z]*f ]] && block 'git clean -f is not allowed'
      done ;;
  esac
  return 0
}

# ------------------------------------------------------------ gh
check_gh_api() {
  local -a a=("$@")
  local n=${#a[@]} j x method="" endpoint="" implicit_post=0 input_file=""
  for ((j = 0; j < n; j++)); do
    x="${a[j]}"
    case "$x" in
      -X|--method) method="${a[j+1]:-}"; ((j++)) ;;
      --method=*) method="${x#*=}" ;;
      -X*) method="${x#-X}" ;;
      -f|-F|--field|--raw-field) implicit_post=1; ((j++)) ;;
      --field=*|--raw-field=*) implicit_post=1 ;;
      -f*|-F*) implicit_post=1 ;;
      --input) implicit_post=1; input_file="${a[j+1]:-}"; ((j++)) ;;
      --input=*) implicit_post=1; input_file="${x#*=}" ;;
      -H|--header|-q|--jq|-t|--template|-p|--preview|--hostname|--cache) ((j++)) ;;
      -*) ;;
      *) [[ -z "$endpoint" ]] && endpoint="$x" ;;
    esac
  done
  method="${method^^}"
  [[ -z "$method" ]] && { ((implicit_post)) && method=POST || method=GET; }

  if [[ -n "$input_file" && "$input_file" != "-" ]]; then
    local f="$input_file"
    [[ "$f" != /* && "$f" != [A-Za-z]:* ]] && f="$hook_cwd/$f"
    if [[ -r "$f" ]] && grep -qiE '"event"[[:space:]]*:[[:space:]]*"approve"' "$f"; then
      block 'PR reviews may only use event COMMENT, never APPROVE'
    fi
  fi

  local re_ok='^/?repos/[^/]+/[^/]+/(pulls/[0-9]+/(reviews|reviews/[0-9]+/events|comments|comments/[0-9]+/replies)|issues/[0-9]+/comments)/?(\?.*)?$'
  case "$method" in
    GET|HEAD) return 0 ;;
    POST)
      [[ "$endpoint" =~ $re_ok ]] ||
        block "gh api POST is only allowed to PR review/comment endpoints (got '${endpoint:-<none>}')" ;;
    *) block "gh api $method is not allowed" ;;
  esac
  return 0
}

check_gh() {
  local -a a=("$@")
  local x
  case "${a[0]:-} ${a[1]:-}" in
    "pr merge"*|"pr close"*) block "gh ${a[0]} ${a[1]} is not allowed; humans merge/close PRs" ;;
    "pr review"*)
      for x in "${a[@]:2}"; do
        [[ "$x" == "--approve" || "$x" =~ ^-[a-zA-Z]*a ]] && block 'gh pr review --approve is not allowed; use --comment'
      done ;;
    "repo delete"*|"repo edit"*|"repo archive"*|"repo rename"*) block "gh ${a[0]} ${a[1]} is not allowed" ;;
    "api "*) check_gh_api "${a[@]:1}" ;;
  esac
  return 0
}

# ------------------------------------------------------------ per-segment
check_segment() {
  local seg="$1"
  local -a t
  read -r -a t <<< "$seg" || true
  ((${#t[@]} == 0)) && return 0
  local i
  for i in "${!t[@]}"; do t[i]="${t[i]//\"/}"; t[i]="${t[i]//\'/}"; done

  # Strip env assignments, wrappers and `sh -c` so the real command is checked.
  local name
  while ((${#t[@]} > 0)); do
    name="${t[0]//\\//}"; name="${name##*/}"; name="${name%.exe}"; name="${name,,}"
    if [[ "${t[0]}" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; then t=("${t[@]:1}"); continue; fi
    case "$name" in
      env|command|builtin|exec|eval|time|nice|nohup|stdbuf|then|do|else|'!'|'{'|'}'|'&')
        t=("${t[@]:1}"); continue ;;
      xargs|timeout)
        t=("${t[@]:1}")
        while ((${#t[@]} > 0)) && [[ "${t[0]}" == -* || "${t[0]}" =~ ^[0-9.]+[smhd]?$ ]]; do t=("${t[@]:1}"); done
        continue ;;
      bash|sh|zsh|dash|ksh|pwsh|powershell)
        if ((${#t[@]} > 1)) && [[ "${t[1],,}" =~ ^-(c|[a-z]*c|command)$ ]]; then t=("${t[@]:2}"); continue; fi ;;
    esac
    break
  done
  ((${#t[@]} == 0)) && return 0
  local -a args=("${t[@]:1}")
  local joined=" ${args[*]} " x

  # Secret files: any read/copy/redirect of them.
  case "$name" in
    cat|less|more|head|tail|bat|nl|tac|strings|xxd|od|hexdump|base64|grep|egrep|fgrep|rg|ag|awk|gawk|sed|sort|uniq|cut|diff|source|.|type|get-content|gc|scp|rsync|tar|zip|7z|openssl|gpg|python|python3|node)
      for x in "${args[@]}"; do is_secret_path "$x" && block "reading secrets ($x) is not allowed"; done ;;
    cp|mv|copy-item|move-item)
      ((${#args[@]} > 1)) && for x in "${args[@]:0:${#args[@]}-1}"; do
        [[ "$x" == -* ]] && continue
        is_secret_path "$x" && block "copying secrets ($x) is not allowed"
      done ;;
  esac
  local prev=""
  for x in "${t[@]}"; do
    if [[ "$prev" == "<" ]] && is_secret_path "$x"; then block "reading secrets ($x) is not allowed"; fi
    if [[ "$x" == \<?* && "$x" != "<<"* ]] && is_secret_path "${x#<}"; then block "reading secrets (${x#<}) is not allowed"; fi
    prev="$x"
  done

  case "$name" in
    sudo|doas|runas) block "$name is not allowed" ;;
    curl|wget|iwr|irm|invoke-webrequest|invoke-restmethod|start-bitstransfer)
      block "$name is not allowed (no ad-hoc network downloads/uploads)" ;;
    kubectl|helm|aws) block "$name is not allowed from this project" ;;
    terraform)
      for x in "${args[@]}"; do
        [[ "$x" == "apply" || "$x" == "destroy" ]] && block "terraform $x is not allowed"
      done ;;
    docker)
      [[ "$joined" =~ [[:space:]](system|volume)[[:space:]]+prune[[:space:]] ]] &&
        block "docker ${BASH_REMATCH[1]} prune is not allowed" ;;
    rm|remove-item|ri|del|erase|rd|rmdir)
      local rec=0 frc=0
      for x in "${args[@]}"; do
        [[ "$name" != rm ]] && x="${x,,}"
        case "$x" in
          --recursive|-recurse) rec=1 ;;
          --force) frc=1 ;;
          --*) ;;
          -*)
            if [[ "$name" == rm ]]; then
              [[ "$x" =~ ^-[a-zA-Z]*[rR] ]] && rec=1
              [[ "$x" =~ ^-[a-zA-Z]*[fF] ]] && frc=1
            else
              [[ "$x" =~ ^-r(e|ec|ecu|ecur|ecurs|ecurse)?$ ]] && rec=1
              [[ "$x" =~ ^-fo(r|rc|rce)?$ ]] && frc=1
            fi ;;
        esac
      done
      ((rec && frc)) && block "recursive force delete ($name ${args[*]}) is not allowed" ;;
    find)
      [[ "$joined" =~ [[:space:]]-delete[[:space:]] ]] && block 'find -delete is not allowed'
      [[ "$joined" =~ [[:space:]]-(exec|execdir|ok|okdir)[[:space:]]+([^[:space:]]*/)?rm[[:space:]] ]] &&
        block 'find -exec rm is not allowed' ;;
    git) check_git "${args[@]}" ;;
    gh) check_gh "${args[@]}" ;;
  esac
  return 0
}

# ------------------------------------------------------------ split + run
body="$(strip_heredocs "$cmd")"
body="${body//\\$'\n'/ }"                                    # line continuations
body="$(printf '%s' "$body" | sed -E 's/[0-9]*>&[0-9-]+//g; s/&>/>/g')"  # 2>&1, &> redirects
body="${body//\$(/$'\n'}"
body="${body//\`/$'\n'}"
for sep in '&&' '||' '|&' ';' '|' '&' '(' ')'; do
  body="${body//"$sep"/$'\n'}"
done

while IFS= read -r segment || [[ -n "$segment" ]]; do
  [[ -z "${segment//[[:space:]]/}" ]] && continue
  check_segment "$segment"
done <<< "$body"

exit 0

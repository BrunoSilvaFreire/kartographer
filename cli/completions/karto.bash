# bash completion for karto / kartographer
#
# Everything past the subcommand comes from the daemon, which answers from an already-compiled
# pipeline for the current directory (~0.2s): task paths, each task's property flags, and the
# accepted values for a flag. With no daemon running the CLI prints nothing and TAB offers
# nothing — it deliberately never starts one, since that would freeze the shell.

_karto() {
    local cur="${COMP_WORDS[COMP_CWORD]}"
    local prev="${COMP_WORDS[COMP_CWORD-1]}"

    # -d/-p take a path argument; let readline do files and directories.
    case "$prev" in
        -d|--project-dir)
            COMPREPLY=( $(compgen -d -- "$cur") )
            return
            ;;
        -p|--pipeline)
            COMPREPLY=( $(compgen -f -- "$cur") )
            return
            ;;
    esac

    # The subcommand slot is static, and the daemon would answer this position with task names.
    local i sawcmd=0
    for (( i = 1; i < COMP_CWORD; i++ )); do
        case "${COMP_WORDS[i]}" in
            tasks|graph|run) sawcmd=1; break ;;
        esac
    done
    if (( ! sawcmd )); then
        COMPREPLY=( $(compgen -W "tasks graph run -p --pipeline -d --project-dir -h --help" -- "$cur") )
        return
    fi

    local candidates
    candidates="$(
        _KARTO_COMPLETE=bash \
        COMP_WORDS="${COMP_WORDS[*]}" \
        COMP_CWORD="$COMP_CWORD" \
        "${COMP_WORDS[0]}" "${COMP_WORDS[@]:1}" 2>/dev/null
    )"

    # --help is the CLI's own, so the daemon never reports it.
    if [[ "$cur" == -* ]]; then
        candidates="$candidates --help"
    fi

    COMPREPLY=( $(compgen -W "$candidates" -- "$cur") )
}

complete -F _karto karto kartographer

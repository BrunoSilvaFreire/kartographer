# bash completion for karto / kartographer
#
# Task names come from the daemon, which answers from an already-compiled pipeline for the
# current directory (~0.2s). With no daemon running the CLI prints nothing and TAB just
# offers nothing — it deliberately never starts one, since that would freeze the shell.

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

    if [[ "$cur" == -* ]]; then
        COMPREPLY=( $(compgen -W "-p --pipeline -d --project-dir -h --help" -- "$cur") )
        return
    fi

    # The subcommand slot is static; the daemon only knows about tasks and would answer
    # this position with task names.
    local i sawcmd=0
    for (( i = 1; i < COMP_CWORD; i++ )); do
        case "${COMP_WORDS[i]}" in
            tasks|graph|run) sawcmd=1; break ;;
        esac
    done
    if (( ! sawcmd )); then
        COMPREPLY=( $(compgen -W "tasks graph run" -- "$cur") )
        return
    fi

    COMPREPLY=( $(compgen -W "$(
        _KARTO_COMPLETE=bash \
        COMP_WORDS="${COMP_WORDS[*]}" \
        COMP_CWORD="$COMP_CWORD" \
        "${COMP_WORDS[0]}" "${COMP_WORDS[@]:1}" 2>/dev/null
    )" -- "$cur") )
}

complete -F _karto karto kartographer

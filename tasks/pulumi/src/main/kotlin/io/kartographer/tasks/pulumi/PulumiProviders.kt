package io.kartographer.tasks.pulumi

import io.kartographer.core.Provider
import io.kartographer.core.providers.fromCommand

/**
 * A value a Pulumi stack exported — `pulumi stack output <name>`.
 *
 * Contributes to task state by default: a stack output is exactly the kind of
 * thing whose change should re-run whatever consumes it. Pass `secret = true`
 * for an output Pulumi marks secret, which also stops it contributing.
 */
fun fromPulumiOutput(
    cwd: String,
    stack: String,
    name: String,
    secret: Boolean = false,
    /**
     * Extra environment for the command — `PULUMI_CONFIG_PASSPHRASE_FILE`, most
     * often, since a long-lived daemon cannot be handed a passphrase through the
     * shell that started the run.
     */
    environment: Map<String, String> = emptyMap(),
    pulumiBin: String = "pulumi",
): Provider<String> = fromCommand(
    executable = pulumiBin,
    arguments = buildList {
        add("stack")
        add("output")
        add(name)
        add("--stack"); add(stack)
        add("--cwd"); add(cwd)
        if (secret) add("--show-secrets")
    },
    describedAs = "<pulumi-output $stack#$name>",
    environment = environment,
    secret = secret,
    contributesToState = !secret,
)

/**
 * A configuration value of a Pulumi stack — `pulumi config get <key>`.
 *
 * Secret by default: this is what a stack's encrypted config is for, and it is
 * how `backend/karto.kts` was already reaching for database passwords.
 */
fun fromPulumiConfig(
    cwd: String,
    stack: String,
    key: String,
    secret: Boolean = true,
    /** Extra environment, e.g. `PULUMI_CONFIG_PASSPHRASE_FILE`. */
    environment: Map<String, String> = emptyMap(),
    pulumiBin: String = "pulumi",
): Provider<String> = fromCommand(
    executable = pulumiBin,
    arguments = listOf("config", "get", key, "--stack", stack, "--cwd", cwd),
    describedAs = "<pulumi-config $stack#$key>",
    environment = environment,
    secret = secret,
    contributesToState = !secret,
)

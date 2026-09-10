package io.kartographer.tasks.kubernetes

import io.kartographer.core.Provider
import io.kartographer.core.providers.fromCommand
import java.util.Base64

/**
 * The value of one key in a Kubernetes secret.
 *
 * Reads the base64 payload with `jsonpath` and decodes it here rather than
 * piping through `base64 -d`: a pipeline in a command line means a shell, and a
 * shell means the value can end up in a process listing or a trace.
 *
 * Marked secret by default, so it never reaches a task state file, a log line or
 * a remote worker's request.
 */
fun fromKubernetesSecret(
    name: String,
    key: String,
    namespace: String? = null,
    kubeContext: String? = null,
    /**
     * A kubeconfig file, when the cluster is not the one the default kubeconfig
     * points at. Passed as `KUBECONFIG` rather than `--kubeconfig` so it behaves
     * the same as running the command by hand.
     */
    kubeconfig: String? = null,
    kubectlBin: String = "kubectl",
    secret: Boolean = true,
): Provider<String> = fromCommand(
    executable = kubectlBin,
    arguments = buildList {
        if (kubeContext != null) add("--context=$kubeContext")
        if (namespace != null) add("--namespace=$namespace")
        add("get")
        add("secret")
        add(name)
        add("--output=jsonpath={.data.$key}")
    },
    describedAs = "<k8s-secret ${namespace ?: "-"}/$name#$key>",
    environment = kubeconfig?.let { mapOf("KUBECONFIG" to it) } ?: emptyMap(),
    secret = secret,
) { stdout ->
    val encoded = stdout.trim()
    if (encoded.isEmpty()) {
        throw IllegalStateException(
            "Secret '$name' in namespace '${namespace ?: "(current)"}' has no key '$key'."
        )
    }
    String(Base64.getDecoder().decode(encoded))
}

/**
 * The value of one key in a ConfigMap. Not secret by default, and contributing
 * to task state, because a changed config value is exactly the sort of thing a
 * task that reads it should be re-run for.
 */
fun fromKubernetesConfigMap(
    name: String,
    key: String,
    namespace: String? = null,
    kubeContext: String? = null,
    kubeconfig: String? = null,
    kubectlBin: String = "kubectl",
    contributesToState: Boolean = true,
): Provider<String> = fromCommand(
    executable = kubectlBin,
    arguments = buildList {
        if (kubeContext != null) add("--context=$kubeContext")
        if (namespace != null) add("--namespace=$namespace")
        add("get")
        add("configmap")
        add(name)
        add("--output=jsonpath={.data.$key}")
    },
    describedAs = "<k8s-configmap ${namespace ?: "-"}/$name#$key>",
    environment = kubeconfig?.let { mapOf("KUBECONFIG" to it) } ?: emptyMap(),
    contributesToState = contributesToState,
)

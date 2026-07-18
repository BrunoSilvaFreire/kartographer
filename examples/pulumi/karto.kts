import io.kartographer.tasks.pulumi.*

task<PulumiUp>("pl-up") {
    stack.set("dev")
    cwd.set("infra/")
}

task<PulumiTask>("pl-default") {
    arg("--yes")
}

task<PulumiPreview>("pl-preview") {
    stack.set("dev")
}

task<PulumiRefresh>("pl-refresh") {
    stack.set("dev")
}

task<PulumiDestroy>("pl-destroy") {
    stack.set("dev")
}

import io.kartographer.tasks.ansible.*

task<AnsiblePlaybookRun>("ans-run") {
    playbook.set("deploy.yml")
    inventory.set("hosts.ini")
}

task<AnsiblePlaybookRun>("ans-run-complex") {
    playbook.set("deploy.yml")
    inventory.set("hosts.ini")
    tags.add("setup")
    extraVars["foo"] = "bar"
    arg("--verbose")
}

task<AnsiblePlaybookRun>("ans-run-minimal") {
    playbook.set("deploy.yml")
}

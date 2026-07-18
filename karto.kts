import io.kartographer.tasks.gradle.GradleTask

project {
    rootDirectory = "."
}

task<GradleTask>("gherkin") {
    useWrapper = true
    tasks.add(":integration-tests:test")
    extraArgs.add("--tests")
    extraArgs.add("io.kartographer.integration.CucumberTestRunner")
}

task<GradleTask>("test") {
    useWrapper = true
    tasks.add("test")
}

task<GradleTask>("coverage") {
    useWrapper = true
    tasks.add(":integration-tests:jacocoTestReport")
    dependsOn("test")
}

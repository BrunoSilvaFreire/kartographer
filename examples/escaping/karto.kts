import io.kartographer.tasks.DefaultTask

task<DefaultTask>("escape-test") {
    // We will add steps in the tests, but let's define it so it exists.
}
task<DefaultTask>("env-test") {
    // We will define this in test
}
task<DefaultTask>("elevated-task") {
    // We will define this in test
}

import io.kartographer.tasks.flutter.*

task<FlutterRun>("fl-run") {
    device.set("web-server")
    mode.set("profile")
    target.set("lib/app.dart")
}

task<FlutterBuild>("fl-build") {
    targetPlatform.set("web")
    config.set("prod")
}

task<FlutterRun>("fl-run-release") {
    device.set("web-server")
    mode.set("release")
    config.set("my-config.yaml")
    webPort.set("8080")
    webHostname.set("0.0.0.0")
    arg("--web-only")
}

task<FlutterBuild>("fl-build-yaml") {
    targetPlatform.set("apk")
    config.set("another-config.yaml")
    arg("--split-per-abi")
}

task<FlutterTest>("fl-test") {
    arg("--coverage")
}

task<FlutterRun>("fl-run-linux") {
    device.set("linux")
}

import io.kartographer.tasks.apt.*

val pkg = task<AptPackage>("apt-pkg") {
    packageName.set("my-service")
    version.set("2.1.0")
    fromFile("src/app.bin", "/usr/bin/app")
}

task<AptPackage>("apt-pkg-minimal") {
    from("src/configs", "/etc/configs")
}

task<AptInstall>("apt-inst") {
    install("my-service_2.1.0_all.deb")
}

task<AptInstall>("apt-inst-provider") {
    install(pkg.outputDeb)
}

task<AptInstall>("apt-inst-empty") {
    // Empty install task to test failure path
}

task<AptInstall>("apt-inst-prop-null") {
    val prop = io.kartographer.core.Property<String>().apply { set("my-service_2.1.0_all.deb") }
    install(prop)
}

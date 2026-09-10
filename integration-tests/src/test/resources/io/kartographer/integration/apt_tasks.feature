@smoke @integration
Feature: Apt Tasks
  Verify that AptPackage and AptInstall build the correct dpkg-deb and apt-get commands.

  Scenario: Package files into a debian package
    Given a temporary directory for packaging
    And a package file "src/app.bin" with content "binary"
    And we load apt script "examples/apt/karto.kts"
    When we dry-run the AptPackage task "apt-pkg"
    Then the executed apt command should start with "dpkg-deb --build"

  Scenario: Install debian packages using apt-get
    Given we load apt script "examples/apt/karto.kts"
    When we dry-run the AptInstall task "apt-inst"
    Then the executed apt command should end with "apt-get install -y"

  Scenario: Package directory into a debian package with minimal config
    Given a temporary directory for packaging
    And a package file "src/configs/app.conf" with content "port=80"
    And we load apt script "examples/apt/karto.kts"
    When we dry-run the AptPackage task "apt-pkg-minimal"
    Then the executed apt command should start with "dpkg-deb --build"

  Scenario: Install debian package using a provider output
    Given a temporary directory for packaging
    And a package file "src/app.bin" with content "binary"
    And we load apt script "examples/apt/karto.kts"
    When we dry-run the AptInstall task "apt-inst-provider"
    Then the executed apt command should end with "apt-get install -y"

  Scenario: Install empty package list fails
    Given we load apt script "examples/apt/karto.kts"
    When we dry-run the AptInstall task "apt-inst-empty" and expect failure
    Then the apt task execution should fail with IllegalArgumentException

  Scenario: Install debian package using a provider with null producing task
    Given we load apt script "examples/apt/karto.kts"
    When we dry-run the AptInstall task "apt-inst-prop-null"
    Then the executed apt command should end with "apt-get install -y"

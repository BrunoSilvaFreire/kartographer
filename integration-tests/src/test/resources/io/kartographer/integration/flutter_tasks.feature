@smoke @integration
Feature: Flutter Tasks
  Verify that FlutterRun, FlutterBuild, and FlutterTest build the correct commands.

  Scenario: Run a Flutter app in profile mode on web-server
    Given we load flutter script "examples/flutter/karto.kts"
    When we dry-run the FlutterRun task "fl-run"
    Then the executed flutter command should start with "flutter run -d web-server --profile -t lib/app.dart"

  Scenario: Build a Flutter web app
    Given we load flutter script "examples/flutter/karto.kts"
    When we dry-run the FlutterBuild task "fl-build"
    Then the executed flutter command should start with "flutter build web --dart-define=CITYSCAPE_CONFIG=config/prod.yaml"

  Scenario: Run a Flutter app in release mode with web port and custom config
    Given we load flutter script "examples/flutter/karto.kts"
    When we dry-run the FlutterRun task "fl-run-release"
    Then the executed flutter command should start with "flutter run -d web-server --release -t lib/main.dart --dart-define=CITYSCAPE_CONFIG=my-config.yaml --web-port 8080 --web-hostname 0.0.0.0 --web-only"

  Scenario: Build a Flutter app with config ending in yaml and arguments
    Given we load flutter script "examples/flutter/karto.kts"
    When we dry-run the FlutterBuild task "fl-build-yaml"
    Then the executed flutter command should start with "flutter build apk --dart-define=CITYSCAPE_CONFIG=another-config.yaml --split-per-abi"

  Scenario: Run Flutter tests
    Given we load flutter script "examples/flutter/karto.kts"
    When we dry-run the FlutterTest task "fl-test"
    Then the executed flutter command should start with "flutter test --coverage"

  Scenario: Run a Flutter app on a non-web device
    Given we load flutter script "examples/flutter/karto.kts"
    When we dry-run the FlutterRun task "fl-run-linux"
    Then the executed flutter command should start with "flutter run -d linux"

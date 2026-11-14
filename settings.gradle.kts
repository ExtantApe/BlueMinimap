rootProject.name = "BlueMapMini"

// Test-only plugins for the live tests in test/live. They're never shipped.
include("live-test-helper", "live-test-conflict")
project(":live-test-helper").projectDir = file("test/live/helper")
project(":live-test-conflict").projectDir = file("test/live/conflict")

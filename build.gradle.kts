plugins {
    // Declared here with `apply false` so the versions are named once and the modules ask by bare id.
    // Asking for a version in a module as well is refused when the root applies a plugin from the
    // same jar: "plugin is already on the classpath with an unknown version".
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.pluginSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sborkaKmp) apply false
    alias(libs.plugins.sborkaJvm) apply false
    alias(libs.plugins.sborkaLint) apply false
    alias(libs.plugins.sborkaPublish) apply false
}

// The group, the version and the ktlint wiring used to be handed out from a `subprojects { }` block
// here, and `libVersion()` composed `0.1.<BUILD_NUMBER>` at the bottom of the file. All of it is
// `gradle.properties` now — `sborka.group` and `version` — applied per module by
// `ru.workinprogress.sborka.base`. CI passes the composed number as `-PVERSION`, which is the same
// scheme with one property fewer.

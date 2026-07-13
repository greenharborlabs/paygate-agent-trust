plugins {
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    java
    pmd
    id("com.github.spotbugs") version "6.5.8"
}

group = "com.greenharborlabs"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://central.sonatype.com/repository/maven-snapshots/")
}

val paygateVersion = "0.1.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.19.0")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("com.greenharborlabs:paygate-spring-boot-starter:$paygateVersion")
    implementation("com.greenharborlabs:paygate-lightning-lnbits:$paygateVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

pmd {
    toolVersion = "7.26.0"
    isConsoleOutput = false
    isIgnoreFailures = false
    ruleSets = listOf()
    ruleSetFiles = files("config/pmd/ruleset.xml")
}

spotbugs {
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.MEDIUM
    ignoreFailures = false
    excludeFilter = file("config/spotbugs/exclude.xml")
}

tasks.withType<Pmd>().configureEach {
    reports {
        html.required = true
        xml.required = false
    }
}

tasks.named<Pmd>("pmdTest") {
    enabled = false
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports {
        create("html") {
            required = true
        }
        create("xml") {
            required = false
        }
    }
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsTest") {
    enabled = false
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

tasks.register("verifyStaticAnalysisGate") {
    group = "verification"
    description = "Verify that main-source static analysis failures remain blocking."

    doLast {
        val pmdExtension = project.extensions.getByType(
            org.gradle.api.plugins.quality.PmdExtension::class.java
        )
        val spotBugsExtension = project.extensions.getByType(
            com.github.spotbugs.snom.SpotBugsExtension::class.java
        )

        check(!pmdExtension.isIgnoreFailures) {
            "PMD findings must remain blocking (pmd.ignoreFailures=false)."
        }
        check(!spotBugsExtension.ignoreFailures.get()) {
            "SpotBugs findings must remain blocking (spotbugs.ignoreFailures=false)."
        }
    }
}

tasks.named("check") {
    dependsOn("verifyStaticAnalysisGate", "pmdMain", "spotbugsMain")
}

tasks.register("quality") {
    group = "verification"
    description = "Run static analysis and compile/test validation."
    dependsOn("check")
}

plugins {
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    java
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

tasks.bootJar {
    archiveFileName.set("app.jar")
}

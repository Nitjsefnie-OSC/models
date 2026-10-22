description = "Spring Boot auto-configuration starter for Models and Spring AI"

val springAiVersion = providers.gradleProperty("springAiVersion").getOrElse("2.0.0")
val springBootVersion = providers.gradleProperty("springBootVersion").getOrElse("4.1.0")
val springFrameworkVersion = providers.gradleProperty("springFrameworkVersion").getOrElse("7.0.8")
val micrometerObservationVersion =
    providers.gradleProperty("micrometerObservationVersion").getOrElse("1.16.6")

dependencies {
    api(project(":models-spring-ai"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    compileOnly("org.springframework.ai:spring-ai-model:$springAiVersion")
    // ChatClient lives here, and it stays compileOnly: an application that only wants the
    // ChatModel adapter must not be made to carry the client.
    compileOnly("org.springframework.ai:spring-ai-client-chat:$springAiVersion")
    // The engine, for integrallis.models.catalog-model. compileOnly on purpose: an application
    // that supplies its own model bean needs no decoder from this starter, and one that uses the
    // property has backend-java on the classpath anyway.
    compileOnly(project(":backend-java"))
    annotationProcessor(
        "org.springframework.boot:spring-boot-configuration-processor:$springBootVersion"
    )

    testImplementation("org.springframework.boot:spring-boot-test:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator:$springBootVersion")
    testImplementation("org.springframework:spring-test:$springFrameworkVersion")
    testImplementation("org.springframework.ai:spring-ai-model:$springAiVersion")
    testImplementation("org.springframework.ai:spring-ai-client-chat:$springAiVersion")
    testImplementation(project(":backend-java"))
    testImplementation("org.springframework.ai:spring-ai-vector-store:$springAiVersion")
    testImplementation("io.micrometer:micrometer-observation:$micrometerObservationVersion")
    testImplementation(
        "com.integrallis:vectors-spring-boot-starter:" +
            providers.gradleProperty("vectorsVersion").get()
    )
}

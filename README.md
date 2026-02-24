# WebFlux Functional Endpoints Development Assistant

> A **dev-time helper** for Spring WebFlux **functional endpoints (WebFlux.fn)**.  
> It statically analyzes your source code and **generates Swagger(OpenAPI) JSON / AsyncAPI JSON / router–handler boilerplate / entity field enums**.

## What does it do?

This project is designed to automate repetitive tasks while you are coding locally:

- **Swagger(OpenAPI) JSON generation**
  - Parses `RouterFunction`-based routing and produces `swagger.json`.
  - (Optional) supports a MVC(@Controller) parsing mode.
- **AsyncAPI JSON generation (RSocket)**
  - Parses `@Controller` / `@MessageMapping` RSocket endpoints and emits AsyncAPI(2.6.0) JSON.
- **RouterFunction → Handler scaffolding & patching**
  - Scans router packages, generates/updates handler classes, and fixes handler references (`handler::method`).
- **Entity → Fields Enum + CollectionNames Enum generation**
  - (Default) scans Mongo entities annotated with `@Document` and generates:
    - `{Entity}Fields` enum (field/raw mapping)
    - `CollectionNames` enum (collection names)

> ⚠️ This is **not** a “cover every possible pattern” generator.  
> It’s meant to fit a **specific development style/pattern** and speed up your workflow.

---

## Requirements

- **Java 21**
- Spring WebFlux / Reactor (declared as `compileOnly` for the library itself)

---

## Installation

The repository contains Maven Central publishing configuration, but availability depends on your publishing workflow (tags/releases may be missing).

### 1) (Recommended) include/build internally

- Add it as a git submodule, or build and publish to your internal repository.

### 2) Maven/Gradle dependency (when published)

Current coordinates from the build script:

- `groupId`: `com.byeolnaerim`
- `artifactId`: `webflux-fe-dev-assistant`
- `version`: `<version>`

**Gradle**

```gradle
dependencies {
  implementation("com.byeolnaerim:webflux-fe-dev-assistant:<version>")
}
```

**Maven**

```xml
<dependency>
  <groupId>com.byeolnaerim</groupId>
  <artifactId>webflux-fe-dev-assistant</artifactId>
  <version>{version}</version>
</dependency>
```

---

## Quickstart (Spring Boot - local profile)

This is a “rough” usage example.  
The core idea is: start `WatchForMainRun` and wire watcher configs.

```java
@Configuration
@Profile("local")
public class WatchForMainRunSpring {

  private WatchForMainRun runner;

  @Autowired
  private ConfigurableListableBeanFactory beanFactory;

  @PostConstruct
  public void start() {

    // guess base package (Spring Boot AutoConfigurationPackages)
    String base = (beanFactory != null)
        ? AutoConfigurationPackages.get(beanFactory).get(0)
        : "com.byeolnaerim";

    // 1) Entity watcher (Fields enum / CollectionNames enum)
    var entityCfg = EntityFileWatcher.Config.builder()
        .rootPath("src.main.java")
        .fieldPackage(base + ".util.fields")
        .entityPackage(base + ".entity")
        .collectionNamePackage(base + ".util")
        .build();

    // 2) Handler generator (RouterFunction → Handler)
    var handlerCfg = HandlerGenerator.Config.builder()
        .rootPath("src.main.java")
        .handlerOutputDir("src.main.java." + base + ".web.handler")
        .routerPackage("src.main.java." + base + ".web.route")
        .addAutoImport(ResponseWrapper.class)
        .addAutoImport(Result.class)
        .addStaticAutoImport(
            HandlerGenerator.StaticImportSpec.of(ResponseWrapper.class, "response")
        )
        .build();

    // 3) Swagger(OpenAPI) JSON generator
    var swaggerCfg = SwaggerJsonFileWatcher.Config.builder().build();

    // 4) Orchestrator
    var mainCfg = WatchForMainRun.Config.builder()
        .debounceMillis(400)
        .entityConfig(entityCfg)
        .handlerConfig(handlerCfg)
        .swaggerConfig(swaggerCfg)
        .build();

    runner = new WatchForMainRun(mainCfg);

    try {
      runner.start();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @PreDestroy
  public void stop() {
    if (runner != null) runner.stop();
  }
}
```

---

## Configuration Guide

### 1) `WatchForMainRun` (orchestrator)

- `debounceMillis(ms)`  
  Debounces file changes and runs the pipeline once per burst.
- `trigger(Path)`  
  Default: a `.reloadtrigger` file inside the detected classpath root.  
  (Useful to nudge Spring Boot Devtools reload)
- `classpathWatchPaths(String)`  
  Set classpath roots manually (CSV or OS `pathSeparator` supported: `:` / `;`).

> Default behavior: when watchers emit changes, it runs `runGenerateTask()`;  
> when classpath/config changes are detected, it touches `.reloadtrigger`.

---

### 2) `EntityFileWatcher`

**What it does**

- Treats classes annotated with (default) `@org.springframework.data.mongodb.core.mapping.Document` as entities and generates:
  - `{Entity}Fields.java` enum
  - `CollectionNames.java` enum

**Key options**

- `rootPath` (default: `src/main/java`)
- `entityPackage` (default: `{basePackage}.entity`)
- `fieldPackage` (default: `{basePackage}.util.fields`)
- `collectionNamePackage` (default: `{basePackage}.util`)
- `collectionNameEnum` (default: `CollectionNames`)
- `webfluxDocumentName` (default: Mongo `Document` annotation FQN)

**How field “raw names” are resolved**

- String-matches and reads `value/name` from:
  - Spring Data Mongo: `@Field`
  - Spring Data Relational / JPA: `@Column`
  - Jackson: `@JsonProperty`

> It runs with Spoon `noClasspath` and tries to be resilient to missing types,  
> but you may need to tune annotation names per project.

---

### 3) `HandlerGenerator`

**What it does**

- Scans router sources and finds `RouterFunction<ServerResponse>` methods, then:
  - generates/updates handler classes,
  - patches handler references (`handler::methodName`),
  - injects handler parameters and imports when missing.

**Key options**

- `rootPath` (default: `src/main/java`)
- `routerPackage` (default: `{basePackage}.web.route`)
- `handlerPackage` (default: `{basePackage}.web.handler`)
- `handlerOutputDir` (default: `{rootPath}/{handlerPackage}/`)
- `scanWholeProject(true/false)` (default: false)
- `addAutoImport(Class)` / `addStaticAutoImport(StaticImportSpec)`

> The generated handler method body is opinionated (e.g., `ResponseWrapper.response(Result._0)`),  
> so align it with your project by adjusting imports/static imports or customizing the generator.

---

### 4) `SwaggerJsonFileWatcher`

**What it does**

- Watches `src/main/java`, extracts route infos, and writes `swagger.json`.
- `projectMode`:
  - `FUNCTIONAL_ENDPOINT` (RouterFunction style)
  - `MVC` (annotated controllers)

**Default output**

- `src/main/resources/static/swagger.json`

---

### 5) `RsoketAsyncApiJsonFileWatcher` (AsyncAPI for RSocket)

**What it does**

- Parses `@Controller/@MessageMapping` endpoints and emits AsyncAPI JSON.

**Default output**

- `src/main/resources/static/asyncapi-rsocket.json`

To attach it to the orchestrator:

```java
var asyncCfg = RsoketAsyncApiJsonFileWatcher.Config.builder().build();

var mainCfg = WatchForMainRun.Config.builder()
    .entityConfig(entityCfg)
    .handlerConfig(handlerCfg)
    .swaggerConfig(swaggerCfg)
    .asyncApiFactory(() -> new RsoketAsyncApiJsonFileWatcher(asyncCfg))
    .build();
```

---

## Generated/Updated Files

- Swagger(OpenAPI): `src/main/resources/static/swagger.json`
- AsyncAPI(RSocket): `src/main/resources/static/asyncapi-rsocket.json`
- Handler classes: `{handlerOutputDir}/*.java`
- Entity field enums: `{rootPath}/{fieldPackage}/**/*Fields.java`
- Collection enum: `{rootPath}/{collectionNamePackage}/CollectionNames.java`

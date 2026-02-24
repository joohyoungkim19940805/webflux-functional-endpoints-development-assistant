# WebFlux Functional Endpoints Development Assistant

> Spring WebFlux **functional endpoints(WebFlux.fn)** 프로젝트를 위한 “개발 보조(Dev-time)” 라이브러리입니다.  
> 소스 코드를 정적 분석해서 **Swagger(OpenAPI) JSON / AsyncAPI JSON / 라우터-핸들러 보일러플레이트 / 엔티티 필드 Enum** 등을 자동 생성합니다.

## 무엇을 해주나?

이 프로젝트는 “로컬 개발 중” 아래 같은 작업을 자동화하는 걸 목표로 합니다.

- **Swagger(OpenAPI) JSON 자동 생성**
  - `RouterFunction` 기반 라우팅을 파싱해서 `swagger.json`을 만들어줍니다.
  - (옵션) MVC(@Controller) 기반도 파싱 모드로 지원합니다.
- **AsyncAPI JSON 자동 생성 (RSocket)**
  - `@Controller` / `@MessageMapping` 기반 RSocket 엔드포인트를 파싱해서 AsyncAPI(2.6.0) JSON을 생성합니다.
- **RouterFunction → Handler 클래스 스캐폴딩/보정**
  - 라우터 패키지를 스캔하여 라우트 경로 기반으로 핸들러 클래스를 자동 생성/업데이트합니다.
  - 라우터 코드 안의 핸들러 참조(`handler::method`)를 자동으로 맞춰줍니다.
- **Entity → Fields Enum + CollectionNames Enum 생성**
  - (기본) `@Document`(Spring Data MongoDB) 엔티티를 스캔하여
    - `{Entity}Fields` Enum(필드명/매핑명)
    - `CollectionNames` Enum(컬렉션명)
      를 소스 코드로 생성합니다.

> ⚠️ 완전한 “모든 케이스”를 커버하는 사용법/스펙 생성기가 아니라,  
> **내가 쓰는 패턴(관례)에 맞춰 빠르게 개발 흐름을 자동화하는 도구**로 보는 게 정확합니다.

---

## 요구사항

- **Java 21**
- Spring WebFlux / Reactor (라이브러리는 `compileOnly`로 의존)

---

## 설치

이 저장소는 Maven Central 배포 설정을 포함하고 있지만, 태그/릴리즈가 없거나 배포 상태가 환경에 따라 다를 수 있습니다.

### 1) (권장) 로컬/사내 환경에서 소스 포함

- 레포를 submodule로 넣거나, 내부 패키지 저장소에 빌드해서 배포하는 방식이 가장 확실합니다.

### 2) Maven/Gradle 의존성 (배포되어 있는 경우)

`groupId/artifactId/version`은 현재 빌드 스크립트 기준으로 아래와 같습니다:

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

아래는 “대충 이런 식으로 쓴다” 스타일의 예시입니다.  
핵심은 `WatchForMainRun` 오케스트레이터를 띄우고, 각 watcher의 `Config`를 조립하는 것입니다.

```java
@Configuration
@Profile("local")
public class WatchForMainRunSpring {

  private WatchForMainRun runner;

  @Autowired
  private ConfigurableListableBeanFactory beanFactory;

  @PostConstruct
  public void start() {

    // basePackage 추정(스프링 부트 AutoConfigurationPackages)
    String base = (beanFactory != null)
        ? AutoConfigurationPackages.get(beanFactory).get(0)
        : "com.byeolnaerim";

    // 1) Entity watcher (Fields enum / CollectionNames enum 생성)
    var entityCfg = EntityFileWatcher.Config.builder()
        .rootPath("src.main.java")
        .fieldPackage(base + ".util.fields")
        .entityPackage(base + ".entity")
        .collectionNamePackage(base + ".util")
        .build();

    // 2) Handler generator (RouterFunction → Handler 생성/보정)
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
    var swaggerCfg = SwaggerJsonFileWatcher.Config.builder()
        // 기본값 사용 시:
        // - watchDirectory: src/main/java
        // - swaggerOutputFile: src/main/resources/static/swagger.json
        .build();

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

## 설정 가이드

### 1) `WatchForMainRun` (오케스트레이터)

- `debounceMillis(ms)`  
  파일 변경 이벤트를 묶어서(디바운스) 한번에 파이프라인 실행.
- `trigger(Path)`  
  기본값은 **클래스패스 루트**를 추정해 `.reloadtrigger` 파일을 생성/갱신합니다.  
  (Spring Boot Devtools 같은 핫리로드 트리거 용도로 사용)
- `classpathWatchPaths(String)`  
  감시할 classpath 루트들을 직접 지정할 수 있습니다.  
  (CSV 또는 OS pathSeparator(`:` / `;`) 지원)

> 기본 동작: watcher들이 변경을 감지하면 `runGenerateTask()`를 실행하고,  
> classpath(클래스/설정) 변경은 `.reloadtrigger`를 `touch`해서 리로드를 유도합니다.

---

### 2) `EntityFileWatcher`

**역할**

- (기본) `@org.springframework.data.mongodb.core.mapping.Document` 가 붙은 클래스를 엔티티로 보고,
  - `{Entity}Fields.java` enum 생성 (필드 “raw name” 포함)
  - `CollectionNames.java` enum 생성 (컬렉션명 포함)

**주요 옵션**

- `rootPath` (기본: `src/main/java`)
- `entityPackage` (기본: `{basePackage}.entity`)
- `fieldPackage` (기본: `{basePackage}.util.fields`)
- `collectionNamePackage` (기본: `{basePackage}.util`)
- `collectionNameEnum` (기본: `CollectionNames`)
- `webfluxDocumentName` (기본: Mongo `Document` 어노테이션 FQN)

**필드 raw-name 추정 규칙**

- 아래 어노테이션의 value/name을 string match 방식으로 읽습니다:
  - Spring Data Mongo: `@Field`
  - Spring Data Relational / JPA: `@Column`
  - Jackson: `@JsonProperty`

> 클래스패스를 완전히 해석하지 않고(noClasspath) “문자열 기반”으로 어노테이션 이름을 찾습니다.  
> 프로젝트마다 어노테이션 조합이 다르면 `webfluxDocumentName` 등을 조정하세요.

---

### 3) `HandlerGenerator`

**역할**

- Router 패키지(또는 전체 프로젝트)를 스캔해서 `RouterFunction<ServerResponse>` 메서드들을 분석하고,
  - 핸들러 클래스 생성/업데이트
  - 라우터의 핸들러 참조를 `handler::methodName` 형태로 보정
  - 라우터 메서드에 핸들러 파라미터가 없으면 자동으로 주입 + import 추가

**주요 옵션**

- `rootPath` (기본: `src/main/java`)
- `routerPackage` (기본: `{basePackage}.web.route`)
- `handlerPackage` (기본: `{basePackage}.web.handler`)
- `handlerOutputDir` (기본: `{rootPath}/{handlerPackage}/`)
- `scanWholeProject(true/false)` (기본: false)
- `addAutoImport(Class)` / `addStaticAutoImport(StaticImportSpec)`  
  생성되는 핸들러 파일 상단에 import / static import를 자동 주입합니다.

> 핸들러 메서드 기본 바디는 프로젝트 관례(예: `ResponseWrapper.response(Result._0)`)가 들어있으니  
> 실제 프로젝트에 맞게 auto-import/static-import를 맞춰주세요.

---

### 4) `SwaggerJsonFileWatcher`

**역할**

- `src/main/java`를 감시하면서 라우트 정보를 뽑아 `swagger.json`을 생성합니다.
- `projectMode`로
  - `FUNCTIONAL_ENDPOINT` (RouterFunction 기반)
  - `MVC` (컨트롤러 기반)
    를 선택할 수 있습니다.

**기본 출력 경로**

- `src/main/resources/static/swagger.json`

---

### 5) `RsoketAsyncApiJsonFileWatcher` (AsyncAPI for RSocket)

**역할**

- `@Controller/@MessageMapping` 기반 RSocket 엔드포인트를 파싱해 AsyncAPI JSON을 생성합니다.

**기본 출력 경로**

- `src/main/resources/static/asyncapi-rsocket.json`

오케스트레이터에 붙이려면 factory 형태로 등록합니다:

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

## 생성/갱신되는 파일 요약

- Swagger(OpenAPI): `src/main/resources/static/swagger.json`
- AsyncAPI(RSocket): `src/main/resources/static/asyncapi-rsocket.json`
- Handler 클래스: `{handlerOutputDir}/*.java`
- Entity field enum: `{rootPath}/{fieldPackage}/**/*Fields.java`
- Collection enum: `{rootPath}/{collectionNamePackage}/CollectionNames.java`

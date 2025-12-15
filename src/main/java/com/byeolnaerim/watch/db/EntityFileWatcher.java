package com.byeolnaerim.watch.db;


import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.mapping.Document;
import com.byeolnaerim.watch.AbstractWatcher;
import com.byeolnaerim.watch.ProjectDefaults;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spoon.Launcher;
import spoon.compiler.Environment;
import spoon.reflect.code.CtJavaDoc;
import spoon.reflect.code.CtJavaDocTag;
import spoon.reflect.code.CtJavaDocTag.TagType;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.visitor.filter.AbstractFilter;
import spoon.support.JavaOutputProcessor;
// import spoon.support.SpoonClassNotFoundException;


public class EntityFileWatcher extends AbstractWatcher {


	public static final class Config {

		private final String collectionNameEnum;

		private final String collectionNamePackage;

		private final String rootPath;

		private final String fieldPackage;

		private final String entityPackage;

		private Config(
						Builder b
		) {

			this.collectionNameEnum = b.collectionNameEnum;
			this.collectionNamePackage = b.collectionNamePackage.replace( '\\', '/' ).replace( '/', '.' );
			this.rootPath = b.rootPath.replace( '\\', '/' ).replace( '.', '/' );
			this.fieldPackage = b.fieldPackage.replace( '\\', '/' ).replace( '/', '.' );
			this.entityPackage = b.entityPackage.replace( '\\', '/' ).replace( '/', '.' );

		}
		// getters...

		public static Builder builder() {

			return new Builder();

		}

		public static final class Builder {

			private String collectionNameEnum = "CollectionNames"; // 필요시 변경

			private String collectionNamePackage = ProjectDefaults.COLLECTION_NAME_PACKAGE; // dot

			private String rootPath = ProjectDefaults.SRC_MAIN_JAVA; // slash

			private String fieldPackage = ProjectDefaults.FIELD_PACKAGE; // dot

			private String entityPackage = ProjectDefaults.ENTITY_PACKAGE;

			public Builder collectionNameEnum(
				String v
			) {

				this.collectionNameEnum = v;
				return this;

			}

			public Builder collectionNamePackage(
				String v
			) {

				this.collectionNamePackage = v;
				return this;

			}

			public Builder rootPath(
				String v
			) {

				this.rootPath = v;
				return this;

			}

			public Builder fieldPackage(
				String v
			) {

				this.fieldPackage = v;
				return this;

			}

			public Builder entityPackage(
				String v
			) {

				this.entityPackage = v;
				return this;

			}

			public Config build() {

				return new Config( this );

			}

		}

	}

	private final Config config;

	protected final File rootDir;

	protected final JavaOutputProcessor javaOutputProcessor = new JavaOutputProcessor();

	public EntityFileWatcher(
								Config config
	) {

		this.config = config;
		Launcher spoon = new Launcher();
		Environment env = spoon.getEnvironment();
		env.setAutoImports( true );
		env.setNoClasspath( true );
		env.setShouldCompile( true );
		env.setComplianceLevel( 14 );
		rootDir = (spoon
			.getEnvironment()
			.getSourceOutputDirectory()
			.getParentFile()
			.toPath()
			.resolve( this.config.rootPath ))
			.toFile();
		env.setSourceOutputDirectory( rootDir );
		spoon.addInputResource( this.config.rootPath );
		spoon.buildModel();

		try {
			Files.createDirectories( rootDir.toPath() );
			Files.createDirectories( rootDir.toPath().resolve( this.config.fieldPackage.replaceAll( "\\.", "/" ) ) );

		} catch (IOException e1) {
			e1.printStackTrace();

		}

	}

	/** 오케스트레이터가 호출할 실제 작업 (한 번 실행) */
	public Mono<Boolean> runGenerateTask() {

		return generateFiles();

	}

	/** AbstractWatcher에 감시 루트 제공 */
	@Override
	protected Path root() {

		return rootDir.toPath().resolve( config.entityPackage.replace( '.', '/' ) );

	}

	/** 기존 API 유지: 내부적으로 AbstractWatcher.start() 호출 */
	public void startWatching() {

		try {
			super.start();

		} catch (IOException e) {
			throw new RuntimeException( e );

		}

	}

	public Mono<Boolean> generateFiles() {

		Launcher spoon = new Launcher();
		Factory factory = spoon.getFactory();
		Environment env = spoon.getEnvironment();
		env.setAutoImports( true );
		env.setNoClasspath( true );
		env.setShouldCompile( true );
		env.setComplianceLevel( 14 );
		env.setSourceOutputDirectory( rootDir );
		spoon.addInputResource( config.rootPath );
		spoon.buildModel();
		javaOutputProcessor.setFactory( spoon.getFactory() );

		var monoFlux = Mono.fromCallable( () -> factory.Package().getRootPackage().getElements( new AbstractFilter<CtClass<?>>() {

		    @Override
		    public boolean matches(
		        CtClass<?> element
		    ) {
		        // BaseEntity 상속 여부 대신 @Document 애노테이션 존재 여부로 필터링
		        Document document = element.getAnnotation( Document.class );
		        return document != null;
		    }

		} ).stream()
		).map( Flux::fromStream ).flatMapMany( e -> e );
		
		CtJavaDoc javaDoc = factory.Core().createJavaDoc();
		javaDoc.setContent( "Generated by script, do not edit manually" );
		CtJavaDocTag seeTag = factory.Core().createJavaDocTag();
		seeTag.setType( TagType.SEE );
		javaDoc.addTag( seeTag );

		CtEnum<?> ctCollectionNamesEnum;
		String qname = config.collectionNamePackage + "." + config.collectionNameEnum;
		Path enumPath = Paths
			.get(
				config.rootPath,
				config.collectionNamePackage.replace( '.', '/' ),
				config.collectionNameEnum + ".java"
			);

		if (Files.exists( enumPath )) {
			// 파일은 있는데 Spoon 모델에 없을 수 있음 → fallback 생성
			ctCollectionNamesEnum = factory.Enum().get( qname );

			if (ctCollectionNamesEnum == null) {
				ctCollectionNamesEnum = factory.createEnum( qname );
				ctCollectionNamesEnum.addModifier( ModifierKind.PUBLIC );
				seeTag.setContent( ctCollectionNamesEnum.getQualifiedName() );
				ctCollectionNamesEnum.addComment( javaDoc.clone() );

			}

		} else {
			ctCollectionNamesEnum = factory.createEnum( qname );
			ctCollectionNamesEnum.addModifier( ModifierKind.PUBLIC );
			seeTag.setContent( ctCollectionNamesEnum.getQualifiedName() );
			ctCollectionNamesEnum.addComment( javaDoc.clone() );

		}

		var _ctCollectionNamesEnum = ctCollectionNamesEnum;
		// 각 엔티티별 Fields enum 생성 스트림
		Flux<Boolean> fieldsChangedFlux = monoFlux.flatMap( ctClass -> Mono.fromCallable( () -> {
			CtEnum<?> ctEnum = factory.createEnum( config.fieldPackage + "." + ctClass.getSimpleName() + "Fields" );
			seeTag.setContent( ctClass.getQualifiedName() );
			ctEnum.addModifier( ModifierKind.PUBLIC );
			ctEnum.addComment( javaDoc.clone() );

			List<String> fieldNames = Stream
				.concat(
					ctClass == null || ctClass.getAllFields() == null ? Stream.empty() : ctClass.getAllFields().stream(),
					ctClass.getSuperclass() == null || ctClass.getSuperclass().getAllFields() == null ? Stream.empty() : ctClass.getSuperclass().getAllFields().stream()
				)
				.map( CtFieldReference::getSimpleName )
				.distinct()
				.sorted( Comparator.naturalOrder() )
				.toList();

			for (String name : fieldNames) {
				CtEnumValue<?> enumValue = factory.Core().createEnumValue();
				enumValue.setSimpleName( name );
				ctEnum.addEnumValue( enumValue );

			}

			Document document = ctClass.getAnnotation( Document.class );

			if (document != null && document.collection() != null) {
				String collectionName = document.collection();
				var existsEnumValue = _ctCollectionNamesEnum.getEnumValue( collectionName );

				if (existsEnumValue == null) {
					CtEnumValue<?> collectionEnumValue = factory.Core().createEnumValue();
					collectionEnumValue.setSimpleName( collectionName );
					_ctCollectionNamesEnum.addEnumValue( collectionEnumValue );

				}

			}

			// 파일 기록 (변경 시에만)
			return writeCtEnumToFile( ctEnum );

		} ) );
		Mono<Boolean> collectionsChangedMono = Mono.fromCallable( () -> writeCtEnumToFile( _ctCollectionNamesEnum ) );

		return Mono
			.zip(
				collectionsChangedMono.defaultIfEmpty( false ),
				fieldsChangedFlux.reduce( false, (acc, ch) -> acc || ch ).defaultIfEmpty( false )
			)
			.map( t2 -> t2.getT1() || t2.getT2() );

	}

	private boolean writeCtEnumToFile(
		CtEnum<?> e
	)
		throws IOException {

		if (e == null)
			return false; // 방어
		String qn = e.getQualifiedName();
		int i = qn.lastIndexOf( '.' );
		String pkg = (i >= 0) ? qn.substring( 0, i ) : "";
		String simple = e.getSimpleName();
		Path outDir = rootDir.toPath().resolve( pkg.replace( '.', '/' ) );
		String code = (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n") + e.toString() + "\n";
		boolean changed = writeIfChanged( outDir.resolve( simple + ".java" ), code.getBytes( StandardCharsets.UTF_8 ) );
		System.out.println( (changed ? "[Changed] " : "[NoChange] ") + qn );
		return changed;

	}
	//
	// public static void main(
	// String[] args
	// ) {
	//
	// EntityFileWatcher watcher = new EntityFileWatcher(
	// EntityFileWatcher.Config
	// .builder()
	// .collectionNameEnum( "CollectionNames" )
	// .collectionNamePackage( "com.byeolnaerim.util" )
	// .rootPath( "src/main/java" )
	// .fieldPackage( "com.byeolnaerim.util.fields" )
	// .entityPackage( "com.byeolnaerim.entity" )
	// .build()
	// );
	// watcher.runGenerateTask().block();
	//
	// }

}

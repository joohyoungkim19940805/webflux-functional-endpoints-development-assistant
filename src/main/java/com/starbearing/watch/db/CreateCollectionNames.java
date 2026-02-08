// package com.starbearing.watch.db;
//
//
// import java.io.File;
// import java.io.IOException;
// import java.nio.file.Files;
// import java.nio.file.Path;
// import com.github.javaparser.StaticJavaParser;
// import com.github.javaparser.ast.CompilationUnit;
// import com.github.javaparser.ast.body.EnumConstantDeclaration;
// import com.github.javaparser.ast.body.EnumDeclaration;
// import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
//
//
// public class CreateCollectionNames {
//
// private static final String COLLECTION_NAMES_PATH =
// "src/main/java/com/npl/auction/platform/util/CollectionNames.java";
//
// public static final String PACKAGE_NAME = "com.starbearing.util";
//
// public static Class<?> clazz = null;
// static {
//
//
// try {
// clazz = Class.forName( CreateCollectionNames.PACKAGE_NAME + "." + "CollectionNames" );
//
// } catch (ClassNotFoundException e) {
// // TODO Auto-generated catch block
// e.printStackTrace();
//
// CompilationUnit compilationUnit = new CompilationUnit();
// compilationUnit.setPackageDeclaration( PACKAGE_NAME );
// compilationUnit.addEnum( "CollectionNames" ).setPublic( true );
// LexicalPreservingPrinter.setup( compilationUnit );
// File file = new File( COLLECTION_NAMES_PATH );
//
// try {
// saveEnumFile( file.toPath(), compilationUnit );
// clazz = Class.forName( CreateCollectionNames.PACKAGE_NAME + "." + "CollectionNames" );
//
// } catch (IOException | ClassNotFoundException e1) {
// // TODO Auto-generated catch block
// e1.printStackTrace();
//
// }
//
// }
//
// }
//
// public static void addCollectionName(
// String collectionName
// ) {
//
// // try {
// //
// // if (clazz == null) {
// // clazz = Class.forName( CreateCollectionNames.PACKAGE_NAME + "." + "CollectionNames" );
// //
// // }
// //
// // if (! clazz.isEnum() || clazz == null)
// // return;
// //
// // @SuppressWarnings("unchecked")
// // Class<? extends Enum<?>> enumClass = (Class<? extends Enum<?>>) clazz;
// //
// // if (Stream
// // .of( enumClass.getEnumConstants() )
// // .anyMatch( e -> e.name().equals( collectionName ) )) { return; }
// //
// // } catch (ClassNotFoundException e) {
// // e.printStackTrace();
// // return;
// //
// // }
//
// File file = new File( COLLECTION_NAMES_PATH );
//
// CompilationUnit compilationUnit;
//
// EnumDeclaration enumDeclaration;
//
// try {
//
// if (file.exists()) {
// // 파일이 존재하면 로드
// compilationUnit = StaticJavaParser.parse( file );
// LexicalPreservingPrinter.setup( compilationUnit );
//
// enumDeclaration = compilationUnit
// .getEnumByName( "CollectionNames" )
// .orElseThrow( () -> new IllegalStateException( "CollectionNames enum not found in file." ) );
// boolean alreadyExists = enumDeclaration
// .getEntries()
// .stream()
// .map( EnumConstantDeclaration::getNameAsString )
// .anyMatch( name -> name.equals( collectionName ) );
//
// if (alreadyExists) {
// System.out.println( "Collection name already exists: " + collectionName );
// return; // 바로 리턴해서 메소드 종료
//
// }
//
// } else {
// // 파일이 없으면 새로 생성
// compilationUnit = new CompilationUnit();
// compilationUnit.setPackageDeclaration( PACKAGE_NAME );
// enumDeclaration = compilationUnit.addEnum( "CollectionNames" ).setPublic( true );
// LexicalPreservingPrinter.setup( compilationUnit );
//
// }
//
// // 이미 존재하는지 확인 후 추가
// if (enumDeclaration
// .getEntries()
// .stream()
// .noneMatch( entry -> entry.getNameAsString().equals( collectionName ) )//
// ) {
// EnumConstantDeclaration newConstant = new EnumConstantDeclaration( collectionName );
// enumDeclaration.addEntry( newConstant );
// System.out.println( "Added new collection name: " + collectionName );
//
// } else {
// System.out.println( "Collection name already exists: " + collectionName );
//
// }
//
// // 파일 저장
// saveEnumFile( file.toPath(), compilationUnit );
//
// } catch (Exception e) {
// e.printStackTrace();
//
// }
//
// }
//
// public static void removeCollectionName(
// String collectionName
// ) {
//
// File file = new File( COLLECTION_NAMES_PATH );
//
// if (! file.exists()) {
// System.out.println( "CollectionNames file does not exist. Nothing to remove." );
// return;
//
// }
//
// try {
// CompilationUnit compilationUnit = StaticJavaParser.parse( file );
// LexicalPreservingPrinter.setup( compilationUnit );
//
// EnumDeclaration enumDeclaration = compilationUnit
// .getEnumByName( "CollectionNames" )
// .orElseThrow( () -> new IllegalStateException( "CollectionNames enum not found in file." ) );
//
// // Remove the enum constant if it exists
// boolean removed = enumDeclaration.getEntries().removeIf( entry -> entry.getNameAsString().equals(
// collectionName ) );
//
// if (removed) {
// System.out.println( "Removed collection name: " + collectionName );
//
// } else {
// System.out.println( "Collection name not found: " + collectionName );
//
// }
//
// // Save the file
// saveEnumFile( file.toPath(), compilationUnit );
//
// } catch (Exception e) {
// e.printStackTrace();
//
// }
//
// }
//
// private static void saveEnumFile(
// Path path, CompilationUnit compilationUnit
// )
// throws IOException {
//
// String formattedCode = LexicalPreservingPrinter.print( compilationUnit );
// Files.createDirectories( path.getParent() ); // Ensure directories exist
// Files.write( path, formattedCode.getBytes() );
// System.out.println( "Saved file: " + path );
//
// }
//
// }

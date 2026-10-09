package com.parkingwatch.edge;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Reglas de arquitectura verificadas en cada build (RNF-4.1): el dominio y el análisis no dependen
 * de la red ni del hardware, de modo que se puede cambiar el modelo o la cámara sin tocarlos.
 */
@AnalyzeClasses(
    packages = "com.parkingwatch.edge",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainIsIndependent =
      noClasses()
          .that()
          .resideInAPackage("..edge.domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..transport..",
              "..pipeline..",
              "..app..",
              "..frame..",
              "ai.onnxruntime..",
              "org.bytedeco..");

  @ArchTest
  static final ArchRule analysisDoesNotUseTheNetwork =
      noClasses()
          .that()
          .resideInAnyPackage(
              "..tracking..", "..zone..", "..analytics..", "..ocr..", "..detection..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..transport..", "java.net.http..");

  @ArchTest
  static final ArchRule onlyAdaptersTouchNativeLibraries =
      noClasses()
          .that()
          .resideOutsideOfPackages("..frame..", "..detection..", "..ocr..", "..app..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("ai.onnxruntime..", "org.bytedeco..");
}

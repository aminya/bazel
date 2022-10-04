// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.buildjar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.MoreFiles;
import com.google.devtools.build.buildjar.instrumentation.JacocoInstrumentationProcessor;
import com.google.devtools.build.buildjar.javac.BlazeJavacArguments;
import com.google.devtools.build.buildjar.javac.JavacOptions;
import com.google.devtools.build.buildjar.javac.JavacOptions.FilteredJavacopts;
import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin;
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule;
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import javax.annotation.Nullable;

/** All the information needed to perform a single Java library build operation. */
public final class JavaLibraryBuildRequest {
  private ImmutableList<String> javacOpts;

  /** Where to store source files generated by annotation processors. */
  private final Path sourceGenDir;

  /** The path to an output jar for source files generated by annotation processors. */
  private final Path generatedSourcesOutputJar;

  /** The path to an output jar for classfiles generated by annotation processors. */
  private final Path generatedClassOutputJar;

  private final ArrayList<Path> sourceFiles;
  private final ImmutableList<Path> sourceJars;

  private final ImmutableList<Path> sourcePath;
  private final ImmutableList<Path> classPath;
  private final ImmutableList<Path> bootClassPath;
  private final Path system;

  private final ImmutableList<Path> processorPath;
  private final List<String> processorNames;
  private final ImmutableSet<String> builtinProcessorNames;

  private final Path outputJar;
  private final Path nativeHeaderOutput;
  @Nullable private final String targetLabel;
  @Nullable private final String injectingRuleKind;

  private final Path classDir;
  private final Path tempDir;

  private JacocoInstrumentationProcessor jacocoInstrumentationProcessor;

  private final boolean compressJar;

  private final OptionsParser.ReduceClasspathMode reduceClasspathMode;
  private final int fullClasspathLength;
  private final int reducedClasspathLength;

  /** Repository for all dependency-related information. */
  private final DependencyModule dependencyModule;

  /** Repository for information about annotation processor-generated symbols. */
  private final AnnotationProcessingModule processingModule;

  /** List of plugins that are given to javac. */
  private final ImmutableList<BlazeJavaCompilerPlugin> plugins;

  /** Map of inputs' path and digest */
  private final ImmutableMap<String, ByteString> inputsAndDigest;

  private final OptionalInt requestId;

  /**
   * Constructs a build from a list of command args. Sets the same JavacRunner for both compilation
   * and annotation processing.
   *
   * @param optionsParser the parsed command line args.
   * @param extraPlugins extraneous plugins to use in addition to the strict dependency module.
   * @throws InvalidCommandLineException on any command line error
   */
  public JavaLibraryBuildRequest(
      OptionsParser optionsParser, List<BlazeJavaCompilerPlugin> extraPlugins)
      throws InvalidCommandLineException, IOException {
    this(optionsParser, extraPlugins, new DependencyModule.Builder());
  }

  /**
   * Constructs a build from a list of command args. Sets the same JavacRunner for both compilation
   * and annotation processing.
   *
   * @param optionsParser the parsed command line args.
   * @param extraPlugins extraneous plugins to use in addition to the strict dependency module.
   * @param depsBuilder a preconstructed dependency module builder.
   * @throws InvalidCommandLineException on any command line error
   */
  public JavaLibraryBuildRequest(
      OptionsParser optionsParser,
      List<BlazeJavaCompilerPlugin> extraPlugins,
      DependencyModule.Builder depsBuilder)
      throws InvalidCommandLineException, IOException {
    this(optionsParser, extraPlugins, depsBuilder, ImmutableMap.of(), OptionalInt.empty());
  }

  /**
   * Constructs a build from a list of command args. Sets the same JavacRunner for both compilation
   * and annotation processing.
   *
   * @param optionsParser the parsed command line args.
   * @param extraPlugins extraneous plugins to use in addition to the strict dependency module.
   * @param depsBuilder a preconstructed dependency module builder.
   * @param inputsAndDigest a map of inputs' paths and their digest
   * @throws InvalidCommandLineException on any command line error
   */
  public JavaLibraryBuildRequest(
      OptionsParser optionsParser,
      List<BlazeJavaCompilerPlugin> extraPlugins,
      DependencyModule.Builder depsBuilder,
      ImmutableMap<String, ByteString> inputsAndDigest,
      OptionalInt requestId)
      throws InvalidCommandLineException, IOException {
    depsBuilder.setDirectJars(
        optionsParser.directJars().stream().map(Paths::get).collect(toImmutableSet()));
    if (optionsParser.getStrictJavaDeps() != null) {
      depsBuilder.setStrictJavaDeps(optionsParser.getStrictJavaDeps());
    }
    if (optionsParser.getOutputDepsProtoFile() != null) {
      depsBuilder.setOutputDepsProtoFile(Paths.get(optionsParser.getOutputDepsProtoFile()));
    }
    depsBuilder.addDepsArtifacts(asPaths(optionsParser.getDepsArtifacts()));
    depsBuilder.setPlatformJars(
        optionsParser.getBootClassPath().stream().map(Paths::get).collect(toImmutableSet()));
    if (optionsParser.reduceClasspathMode() != OptionsParser.ReduceClasspathMode.NONE) {
      depsBuilder.setReduceClasspath();
    }
    if (optionsParser.getTargetLabel() != null) {
      depsBuilder.setTargetLabel(optionsParser.getTargetLabel());
    }
    this.dependencyModule = depsBuilder.build();
    this.sourceGenDir =
        deriveDirectory(optionsParser.getTargetLabel(), optionsParser.getOutputJar(), "_sources");

    AnnotationProcessingModule.Builder processingBuilder = AnnotationProcessingModule.builder();
    processingBuilder.setSourceGenDir(sourceGenDir);
    if (optionsParser.getManifestProtoPath() != null) {
      processingBuilder.setManifestProtoPath(Paths.get(optionsParser.getManifestProtoPath()));
    }
    this.processingModule = processingBuilder.build();

    ImmutableList.Builder<BlazeJavaCompilerPlugin> pluginsBuilder =
        ImmutableList.<BlazeJavaCompilerPlugin>builder().add(dependencyModule.getPlugin());
    processingModule.registerPlugin(pluginsBuilder);
    pluginsBuilder.addAll(extraPlugins);
    this.plugins = pluginsBuilder.build();

    this.compressJar = optionsParser.compressJar();
    this.reduceClasspathMode = optionsParser.reduceClasspathMode();
    this.fullClasspathLength = optionsParser.fullClasspathLength();
    this.reducedClasspathLength = optionsParser.reducedClasspathLength();
    this.sourceFiles = new ArrayList<>(asPaths(optionsParser.getSourceFiles()));
    this.sourceJars = asPaths(optionsParser.getSourceJars());
    this.classPath = asPaths(optionsParser.getClassPath());
    this.sourcePath = asPaths(optionsParser.getSourcePath());
    this.bootClassPath = asPaths(optionsParser.getBootClassPath());
    this.system = asPath(optionsParser.getSystem());
    this.processorPath = asPaths(optionsParser.getProcessorPath());
    this.processorNames = optionsParser.getProcessorNames();
    this.builtinProcessorNames = ImmutableSet.copyOf(optionsParser.getBuiltinProcessorNames());
    this.classDir =
        deriveDirectory(optionsParser.getTargetLabel(), optionsParser.getOutputJar(), "_classes");
    this.tempDir =
        deriveDirectory(optionsParser.getTargetLabel(), optionsParser.getOutputJar(), "_tmp");
    this.outputJar = asPath(optionsParser.getOutputJar());
    this.nativeHeaderOutput = asPath(optionsParser.getNativeHeaderOutput());
    for (Map.Entry<String, List<String>> entry : optionsParser.getPostProcessors().entrySet()) {
      switch (entry.getKey()) {
        case "jacoco":
          this.jacocoInstrumentationProcessor =
              JacocoInstrumentationProcessor.create(entry.getValue());
          break;
        default:
          throw new AssertionError("unsupported post-processor " + entry.getKey());
      }
    }
    this.javacOpts = ImmutableList.copyOf(optionsParser.getJavacOpts());
    this.generatedSourcesOutputJar = asPath(optionsParser.getGeneratedSourcesOutputJar());
    this.generatedClassOutputJar = asPath(optionsParser.getManifestProtoPath());
    this.targetLabel = optionsParser.getTargetLabel();
    this.injectingRuleKind = optionsParser.getInjectingRuleKind();
    this.inputsAndDigest = inputsAndDigest;
    this.requestId = requestId;
  }

  /**
   * Derive a temporary directory path based on the path to the output jar, to avoid breaking
   * fragile assumptions made by the implementation of javahotswap.
   */
  // TODO(b/169793789): kill this with fire if javahotswap starts using jars instead of classes
  @VisibleForTesting
  static Path deriveDirectory(String label, String outputJar, String suffix) throws IOException {
    checkArgument(label != null, "--target_label is required");
    checkArgument(outputJar != null, "--output is required");
    checkArgument(
        label.contains(":"), "--target_label must be a canonical label (containing a `:`)");

    Path path = Paths.get(outputJar);
    String name = MoreFiles.getNameWithoutExtension(path);
    String base = label.substring(label.lastIndexOf(':') + 1);
    return path.resolveSibling("_javac").resolve(base).resolve(name + suffix);
  }

  private static ImmutableList<Path> asPaths(Collection<String> paths) {
    return paths.stream().map(Paths::get).collect(toImmutableList());
  }

  @Nullable
  private static Path asPath(@Nullable String path) {
    return path != null ? Paths.get(path) : null;
  }

  public ImmutableList<String> getJavacOpts() {
    return javacOpts;
  }

  public Path getSourceGenDir() {
    return sourceGenDir;
  }

  public ImmutableList<Path> getSourcePath() {
    return sourcePath;
  }

  public Path getGeneratedSourcesOutputJar() {
    return generatedSourcesOutputJar;
  }

  public Path getGeneratedClassOutputJar() {
    return generatedClassOutputJar;
  }

  public ArrayList<Path> getSourceFiles() {
    // TODO(cushon): This is being modified after parsing to add files from source jars.
    return sourceFiles;
  }

  public ImmutableList<Path> getSourceJars() {
    return sourceJars;
  }

  public ImmutableList<Path> getClassPath() {
    return classPath;
  }

  public ImmutableList<Path> getBootClassPath() {
    return bootClassPath;
  }

  public Path getSystem() {
    return system;
  }

  public ImmutableList<Path> getProcessorPath() {
    return processorPath;
  }

  public List<String> getProcessors() {
    // TODO(bazel-team): This might be modified by a JavaLibraryBuilder to enable specific
    // annotation processors.
    return processorNames;
  }

  public Path getOutputJar() {
    return outputJar;
  }

  public Path getNativeHeaderOutput() {
    return nativeHeaderOutput;
  }

  public Path getClassDir() {
    return classDir;
  }

  public Path getTempDir() {
    return tempDir;
  }

  public Path getNativeHeaderDir() {
    return getTempDir().resolve("native_headers");
  }

  public JacocoInstrumentationProcessor getJacocoInstrumentationProcessor() {
    return jacocoInstrumentationProcessor;
  }

  public boolean compressJar() {
    return compressJar;
  }

  public OptionsParser.ReduceClasspathMode reduceClasspathMode() {
    return reduceClasspathMode;
  }

  public int fullClasspathLength() {
    return fullClasspathLength;
  }

  public int reducedClasspathLength() {
    return reducedClasspathLength;
  }

  public DependencyModule getDependencyModule() {
    return dependencyModule;
  }

  public AnnotationProcessingModule getProcessingModule() {
    return processingModule;
  }

  public ImmutableList<BlazeJavaCompilerPlugin> getPlugins() {
    return plugins;
  }

  public ImmutableMap<String, ByteString> getInputsAndDigest() {
    return inputsAndDigest;
  }

  public OptionalInt getRequestId() {
    return requestId;
  }

  @Nullable
  public String getTargetLabel() {
    return targetLabel;
  }

  @Nullable
  public String getInjectingRuleKind() {
    return injectingRuleKind;
  }

  @Nullable
  public String getRepositoryName() {
    if (targetLabel == null) {
      return null;
    }
    return targetLabel.substring(targetLabel.indexOf('@') + 1, targetLabel.indexOf('/'));
  }

  public BlazeJavacArguments toBlazeJavacArguments(ImmutableList<Path> classPath) {
    BlazeJavacArguments.Builder builder =
        BlazeJavacArguments.builder()
            .classPath(classPath)
            .classOutput(getClassDir())
            .bootClassPath(getBootClassPath())
            .system(getSystem())
            .sourceFiles(ImmutableList.copyOf(getSourceFiles()))
            .builtinProcessors(builtinProcessorNames)
            .sourcePath(getSourcePath())
            .sourceOutput(getSourceGenDir())
            .processorPath(getProcessorPath())
            .plugins(getPlugins())
            .inputsAndDigest(getInputsAndDigest())
            .requestId(getRequestId())
            .repositoryName(getRepositoryName());
    addJavacArguments(builder);
    // Performance optimization: when reduced classpaths are enabled, stop the compilation after
    // the first diagnostic that would result in fallback to the transitive classpath. The user
    // only sees diagnostics from the fallback compilation, so collecting additional diagnostics
    // from the reduced classpath compilation is a waste of time.
    // Exception: this doesn't hold if annotation processing is enabled, since diagnostics may be
    // resolved during subsequent processing rounds.
    if (reduceClasspathMode.equals(OptionsParser.ReduceClasspathMode.BAZEL_REDUCED)
        && getProcessors().isEmpty()) {
      builder.failFast(getProcessors().isEmpty());
    }
    if (getNativeHeaderOutput() != null) {
      builder.nativeHeaderOutput(getNativeHeaderDir());
    }
    return builder.build();
  }

  /** Constructs a command line that can be used for a javac invocation. */
  void addJavacArguments(BlazeJavacArguments.Builder builder) {
    FilteredJavacopts filtered = JavacOptions.filterJavacopts(getJavacOpts());
    builder.blazeJavacOptions(filtered.bazelJavacopts());

    ImmutableList<String> javacOpts = filtered.standardJavacopts();

    ImmutableList.Builder<String> javacArguments = ImmutableList.builder();

    // default to -implicit:none, but allow the user to override with -implicit:class.
    javacArguments.add("-implicit:none");
    javacArguments.addAll(javacOpts);

    if (!getProcessors().isEmpty() && !getSourceFiles().isEmpty()) {
      // ImmutableSet.copyOf maintains order
      ImmutableSet<String> deduplicatedProcessorNames = ImmutableSet.copyOf(getProcessors());
      javacArguments.add("-processor");
      javacArguments.add(Joiner.on(',').join(deduplicatedProcessorNames));
    } else {
      // This is necessary because some jars contain discoverable annotation processors that
      // previously didn't run, and they break builds if the "-proc:none" option is not passed to
      // javac.
      javacArguments.add("-proc:none");
    }

    for (String option : javacOpts) {
      if (option.startsWith("-J")) { // ignore the VM options.
        continue;
      }
      if (option.equals("-processor") || option.equals("-processorpath")) {
        throw new IllegalStateException(
            "Using "
                + option
                + " in javacopts is no longer supported."
                + " Use a java_plugin() rule instead.");
      }
    }

    builder.javacOptions(javacArguments.build());
  }
}

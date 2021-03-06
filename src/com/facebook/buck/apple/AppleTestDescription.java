/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.AppleBundleDestination;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;

import java.util.Map;
import java.util.Set;

public class AppleTestDescription implements Description<AppleTestDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("apple_test");

  private static final Logger LOG = Logger.get(AppleTestDescription.class);

  /**
   * Flavors for the additional generated build rules.
   */
  private static final Flavor LIBRARY_FLAVOR = ImmutableFlavor.of("apple-test-library");
  private static final Flavor BUNDLE_FLAVOR = ImmutableFlavor.of("apple-test-bundle");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      LIBRARY_FLAVOR, BUNDLE_FLAVOR);

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = Predicates.in(SUPPORTED_FLAVORS);

  private static final Set<Flavor> NON_LIBRARY_FLAVORS = ImmutableSet.of(
      CxxCompilationDatabase.COMPILATION_DATABASE,
      CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);

  private final AppleConfig appleConfig;
  private final AppleLibraryDescription appleLibraryDescription;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final ImmutableMap<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;

  public AppleTestDescription(
      AppleConfig appleConfig,
      AppleLibraryDescription description,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      Map<Flavor, AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms,
      CxxPlatform defaultCxxPlatform) {
    this.appleConfig = appleConfig;
    appleLibraryDescription = description;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.platformFlavorsToAppleCxxPlatforms =
        ImmutableMap.copyOf(platformFlavorsToAppleCxxPlatforms);
    this.defaultCxxPlatform = defaultCxxPlatform;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR) ||
        appleLibraryDescription.hasFlavors(flavors);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    String extension = args.extension.isLeft() ?
        args.extension.getLeft().toFileExtension() :
        args.extension.getRight();
    if (!AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS.contains(extension)) {
      throw new HumanReadableException(
          "Invalid bundle extension for apple_test rule: %s (must be one of %s)",
          extension,
          AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS);
    }
    boolean createBundle = Sets.intersection(
        params.getBuildTarget().getFlavors(),
        NON_LIBRARY_FLAVORS).isEmpty();
    boolean addDefaultPlatform = Sets.difference(
        params.getBuildTarget().getFlavors(),
        NON_LIBRARY_FLAVORS).isEmpty();
    ImmutableSet.Builder<Flavor> extraFlavorsBuilder = ImmutableSet.builder();
    if (createBundle) {
      extraFlavorsBuilder.add(
          LIBRARY_FLAVOR,
          CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR);
    }
    if (addDefaultPlatform) {
      extraFlavorsBuilder.add(defaultCxxPlatform.getFlavor());
    }
    BuildRule library = appleLibraryDescription.createBuildRule(
        params.copyWithChanges(
            AppleLibraryDescription.TYPE,
            BuildTarget.builder(params.getBuildTarget())
                .addAllFlavors(extraFlavorsBuilder.build())
                .build(),
            Suppliers.ofInstance(params.getDeclaredDeps()),
            Suppliers.ofInstance(params.getExtraDeps())),
        resolver,
        args,
        // For now, instead of building all deps as dylibs and fixing up their install_names,
        // we'll just link them statically.
        Optional.of(Linker.LinkableDepType.STATIC));
    if (!createBundle) {
      return library;
    }

    CxxPlatform cxxPlatform;
    try {
      cxxPlatform = cxxPlatformFlavorDomain
          .getValue(params.getBuildTarget().getFlavors())
          .or(defaultCxxPlatform);
    } catch (FlavorDomainException e) {
      throw new HumanReadableException(e, "%s: %s", params.getBuildTarget(), e.getMessage());
    }
    AppleCxxPlatform appleCxxPlatform =
        platformFlavorsToAppleCxxPlatforms.get(cxxPlatform.getFlavor());
    if (appleCxxPlatform == null) {
      throw new HumanReadableException(
          "%s: Apple test requires an Apple platform, found '%s'",
          params.getBuildTarget(),
          cxxPlatform.getFlavor().getName());
    }

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    ImmutableSet<AppleResourceDescription.Arg> resourceDescriptions =
        AppleResources.collectRecursiveResources(
            params.getTargetGraph(),
            ImmutableSet.of(params.getTargetGraph().get(params.getBuildTarget())));
    LOG.debug("Got resource nodes %s", resourceDescriptions);
    ImmutableMap.Builder<Path, AppleBundleDestination> resourceDirsBuilder =
        ImmutableMap.builder();
    resourceDirsBuilder.putAll(args.dirs.get());
    AppleResources.addResourceDirsToBuilder(resourceDirsBuilder, resourceDescriptions);
    ImmutableMap<Path, AppleBundleDestination> resourceDirs = resourceDirsBuilder.build();

    ImmutableMap.Builder<SourcePath, AppleBundleDestination> resourceFilesBuilder =
        ImmutableMap.builder();
    resourceFilesBuilder.putAll(args.files.get());
    AppleResources.addResourceFilesToBuilder(resourceFilesBuilder, resourceDescriptions);
    ImmutableMap<SourcePath, AppleBundleDestination> resourceFiles = resourceFilesBuilder.build();

    CollectedAssetCatalogs collectedAssetCatalogs =
        AppleDescriptions.createBuildRulesForTransitiveAssetCatalogDependencies(
            params,
            sourcePathResolver,
            appleCxxPlatform.getApplePlatform(),
            appleCxxPlatform.getActool());

    Optional<AppleAssetCatalog> mergedAssetCatalog = collectedAssetCatalogs.getMergedAssetCatalog();
    ImmutableSet<AppleAssetCatalog> bundledAssetCatalogs =
        collectedAssetCatalogs.getBundledAssetCatalogs();

    AppleBundle bundle = new AppleBundle(
        params.copyWithChanges(
            AppleBundleDescription.TYPE,
            BuildTarget.builder(params.getBuildTarget()).addFlavors(BUNDLE_FLAVOR).build(),
            // We have to add back the original deps here, since they're likely
            // stripped from the library link above (it doesn't actually depend on them).
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(library)
                    .addAll(mergedAssetCatalog.asSet())
                    .addAll(bundledAssetCatalogs)
                    .addAll(params.getDeclaredDeps())
                    .build()),
            Suppliers.ofInstance(params.getExtraDeps())),
        sourcePathResolver,
        args.extension,
        args.infoPlist,
        Optional.of(library),
        // TODO(user): Use flavors to switch between iOS and OSX layout
        AppleBundleDescription.IOS_APP_SUBFOLDER_SPEC_MAP,
        resourceDirs,
        resourceFiles,
        bundledAssetCatalogs,
        mergedAssetCatalog);

    return new AppleTest(
        appleConfig.getXctoolPath(),
        "iphonesimulator", // TODO(user): Get this from the CxxPlatform.
        "x86_64", // TODO(user): Get this from the CxxPlatform.
        "iPhone 5s", // TODO(user): Get this from the CxxPlatform.
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(bundle)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        sourcePathResolver,
        bundle,
        extension,
        args.contacts.get(),
        args.labels.get());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg implements HasAppleBundleFields {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<Boolean> canGroup;

    // Bundle related fields.
    public Either<AppleBundleExtension, String> extension;
    public Optional<SourcePath> infoPlist;
    public Optional<String> xcodeProductType;
    public Optional<String> resourcePrefixDir;
    public Optional<ImmutableMap<Path, AppleBundleDestination>> dirs;
    public Optional<ImmutableMap<SourcePath, AppleBundleDestination>> files;

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return extension;
    }

    @Override
    public Optional<SourcePath> getInfoPlist() {
      return infoPlist;
    }

    @Override
    public Optional<String> getXcodeProductType() {
      return xcodeProductType;
    }

    public boolean canGroup() {
      return canGroup.or(false);
    }

    @Override
    public ImmutableMap<Path, AppleBundleDestination> getDirs() {
      return dirs.get();
    }

    @Override
    public ImmutableMap<SourcePath, AppleBundleDestination> getFiles() {
      return files.get();
    }
  }
}

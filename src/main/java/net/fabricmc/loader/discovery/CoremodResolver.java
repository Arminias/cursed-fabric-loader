package net.fabricmc.loader.discovery;

import net.fabricmc.loader.FabricLoaderImpl;
import net.fabricmc.loader.api.metadata.*;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.fabricmc.loader.lib.gson.MalformedJsonException;
import net.fabricmc.loader.metadata.*;
import net.fabricmc.loader.util.FileSystemUtil;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import net.fabricmc.loader.util.version.StringVersion;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.ZipError;

public class CoremodResolver extends ModResolver {

	@Override
	public Map<String, ModCandidate> resolve(FabricLoaderImpl loader) throws ModResolutionException {
		ConcurrentMap<String, ModCandidateSet> candidatesById = new ConcurrentHashMap<>();

		long time1 = System.currentTimeMillis();
		Queue<UrlProcessActionCore> allActions = new ConcurrentLinkedQueue<>();
		ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		for (ModCandidateFinder f : candidateFinders) {
			f.findCandidates(loader, (u, requiresRemap) -> {
				UrlProcessActionCore action = new UrlProcessActionCore(loader, candidatesById, u, 0, requiresRemap);
				allActions.add(action);
				pool.execute(action);
			});
		}
		boolean tookTooLong = false;
		Throwable exception = null;
		try {
			pool.shutdown();
			// Comment out for debugging
			pool.awaitTermination(30, TimeUnit.SECONDS);
			for (UrlProcessAction action : allActions) {
				if (!action.isDone()) {
					tookTooLong = true;
				} else {
					Throwable t = action.getException();
					if (t != null) {
						if (exception == null) {
							exception = t;
						} else {
							exception.addSuppressed(t);
						}
					}
				}
			}
		} catch (InterruptedException e) {
			throw new ModResolutionException("Mod resolution took too long!", e);
		}
		if (tookTooLong) {
			throw new ModResolutionException("Mod resolution took too long!");
		}
		if (exception != null) {
			throw new ModResolutionException("Mod resolution failed!", exception);
		}

		long time2 = System.currentTimeMillis();
		Map<String, ModCandidate> result = new HashMap<>();
		for (String s : candidatesById.keySet()) {
			result.put(s, candidatesById.get(s).toSortedSet().iterator().next());
		}

		long time3 = System.currentTimeMillis();
		loader.getLogger().debug("Mod resolution detection time: " + (time2 - time1) + "ms");
		loader.getLogger().debug("Mod resolution time: " + (time3 - time2) + "ms");

		for (ModCandidate candidate : result.values()) {
			if (candidate.getInfo().getSchemaVersion() < ModMetadataParser.LATEST_VERSION) {
				loader.getLogger().warn("Mod ID " + candidate.getInfo().getId() + " uses outdated schema version: " + candidate.getInfo().getSchemaVersion() + " < " + ModMetadataParser.LATEST_VERSION);
			}

			candidate.getInfo().emitFormatWarnings(loader.getLogger());
		}

		return result;
	}

	static class UrlProcessActionCore extends UrlProcessAction {
		UrlProcessActionCore(FabricLoaderImpl loader, Map<String, ModCandidateSet> candidatesById, URL url, int depth, boolean requiresRemap) {
			super(loader, candidatesById, url, depth, requiresRemap);
		}

		@Override
		protected void compute() {
			FileSystemUtil.FileSystemDelegate jarFs;
			Path path, modJson, rootDir;
			URL normalizedUrl;

			loader.getLogger().debug("Testing " + url);

			try {
				path = UrlUtil.asPath(url).normalize();
				// normalize URL (used as key for nested JAR lookup)
				normalizedUrl = UrlUtil.asUrl(path);
			} catch (UrlConversionException e) {
				throw new RuntimeException("Failed to convert URL " + url + "!", e);
			}

			if (Files.isDirectory(path)) {
				// Directory
				modJson = path.resolve("fabric.mod.json");
				rootDir = path;

				if (loader.isDevelopmentEnvironment() && !Files.exists(modJson)) {
					loader.getLogger().warn("Adding directory " + path + " to mod classpath in development environment - workaround for Gradle splitting mods into two directories");
					synchronized (launcherSyncObject) {
						FabricLauncherBase.getLauncher().propose(url);
					}
				}
			} else {
				// JAR file
				try {
					jarFs = FileSystemUtil.getJarFileSystem(path, false);
					modJson = jarFs.get().getPath("fabric.mod.json");
					rootDir = jarFs.get().getRootDirectories().iterator().next();
				} catch (IOException e) {
					throw new RuntimeException("Failed to open mod JAR at " + path + "!");
				} catch (ZipError e) {
					throw new RuntimeException("Jar at " + path + " is corrupted, please redownload it!");
				}
			}

			LoaderModMetadata[] info;

			try {
				info = new LoaderModMetadata[]{ModMetadataParser.parseMetadata(loader.getLogger(), modJson)};
			} catch (ParseMetadataException.MissingRequired e) {
				throw new RuntimeException(String.format("Mod at \"%s\" has an invalid fabric.mod.json file! The mod is missing the following required field!", path), e);
			} catch (MalformedJsonException | ParseMetadataException e) {
				throw new RuntimeException(String.format("Mod at \"%s\" has an invalid fabric.mod.json file!", path), e);
			} catch (NoSuchFileException e) {
				loader.getLogger().warn(String.format("Non-Fabric mod JAR at \"%s\", adding it anyway", path));
				//info = new LoaderModMetadata[0];
				List<String> provides = new ArrayList<>();

				// Optional (mod loading)
				ModEnvironment environment = ModEnvironment.UNIVERSAL; // Default is always universal
				Map<String, List<EntrypointMetadata>> entrypoints = new HashMap<>();
				List<NestedJarEntry> jars = new ArrayList<>();
				List<V1ModMetadata.MixinEntry> mixins = new ArrayList<>();
				String accessWidener = null;

				// Optional (dependency resolution)
				Map<String, ModDependency> depends = new HashMap<>();
				Map<String, ModDependency> recommends = new HashMap<>();
				Map<String, ModDependency> suggests = new HashMap<>();
				Map<String, ModDependency> conflicts = new HashMap<>();
				Map<String, ModDependency> breaks = new HashMap<>();

				// Happy little accidents
				@Deprecated
				Map<String, ModDependency> requires = new HashMap<>();

				// Optional (metadata)
				String name = null;
				String description = null;
				List<Person> authors = new ArrayList<>();
				List<Person> contributors = new ArrayList<>();
				ContactInformation contact = null;
				List<String> license = new ArrayList<>();
				V1ModMetadata.IconEntry icon = null;

				// Optional (language adapter providers)
				Map<String, String> languageAdapters = new HashMap<>();

				// Optional (custom values)
				Map<String, CustomValue> customValues = new HashMap<>();
				info = new LoaderModMetadata[]{
						new V1ModMetadata("coremod" + path.getFileName().toString().replace(".jar", "").toLowerCase().replaceAll("[^a-z]", ""),
								new StringVersion("1.0.0"), provides, environment, entrypoints, jars, mixins, accessWidener, depends, recommends, suggests, conflicts, breaks, requires, name, description, authors, contributors, contact, license, icon, languageAdapters, customValues)
				};
			} catch (IOException e) {
				throw new RuntimeException(String.format("Failed to open fabric.mod.json for mod at \"%s\"!", path), e);
			} catch (Throwable t) {
				throw new RuntimeException(String.format("Failed to parse mod metadata for mod at \"%s\"", path), t);
			}

			for (LoaderModMetadata i : info) {
				ModCandidate candidate = new ModCandidate(i, normalizedUrl, depth, requiresRemap);
				boolean added;

				if (candidate.getInfo().getId() == null || candidate.getInfo().getId().isEmpty()) {
					throw new RuntimeException(String.format("Mod file `%s` has no id", candidate.getOriginUrl().getFile()));
				}

				if (!MOD_ID_PATTERN.matcher(candidate.getInfo().getId()).matches()) {
					List<String> errorList = new ArrayList<>();
					isModIdValid(candidate.getInfo().getId(), errorList);
					StringBuilder fullError = new StringBuilder("Mod id `");
					fullError.append(candidate.getInfo().getId()).append("` does not match the requirements because");

					if (errorList.size() == 1) {
						fullError.append(" it ").append(errorList.get(0));
					} else {
						fullError.append(":");
						for (String error : errorList) {
							fullError.append("\n  - It ").append(error);
						}
					}

					throw new RuntimeException(fullError.toString());
				}

				for (String provides : candidate.getInfo().getProvides()) {
					if (!MOD_ID_PATTERN.matcher(provides).matches()) {
						List<String> errorList = new ArrayList<>();
						isModIdValid(provides, errorList);
						StringBuilder fullError = new StringBuilder("Mod id provides `");
						fullError.append(provides).append("` does not match the requirements because");

						if (errorList.size() == 1) {
							fullError.append(" it ").append(errorList.get(0));
						} else {
							fullError.append(":");
							for (String error : errorList) {
								fullError.append("\n  - It ").append(error);
							}
						}

						throw new RuntimeException(fullError.toString());
					}
				}

				added = candidatesById.computeIfAbsent(candidate.getInfo().getId(), ModCandidateSet::new).add(candidate);

				if (!added) {
					loader.getLogger().debug(candidate.getOriginUrl() + " already present as " + candidate);
				} else {
					loader.getLogger().debug("Adding " + candidate.getOriginUrl() + " as " + candidate);

					List<Path> jarInJars = inMemoryCache.computeIfAbsent(candidate.getOriginUrl().toString(), (u) -> {
						loader.getLogger().debug("Searching for nested JARs in " + candidate);
						loader.getLogger().debug(u);
						Collection<NestedJarEntry> jars = candidate.getInfo().getJars();
						List<Path> list = new ArrayList<>(jars.size());

						jars.stream()
								.map((j) -> rootDir.resolve(j.getFile().replace("/", rootDir.getFileSystem().getSeparator())))
								.forEach((modPath) -> {
									if (!Files.isDirectory(modPath) && modPath.toString().endsWith(".jar")) {
										// TODO: pre-check the JAR before loading it, if possible
										loader.getLogger().debug("Found nested JAR: " + modPath);
										Path dest = inMemoryFs.getPath(UUID.randomUUID() + ".jar");

										try {
											Files.copy(modPath, dest);
										} catch (IOException e) {
											throw new RuntimeException("Failed to load nested JAR " + modPath + " into memory (" + dest + ")!", e);
										}

										list.add(dest);
									}
								});

						return list;
					});

					if (!jarInJars.isEmpty()) {
						invokeAll(
								jarInJars.stream()
										.map((p) -> {
											try {
												return new UrlProcessAction(loader, candidatesById, UrlUtil.asUrl(p.normalize()), depth + 1, requiresRemap);
											} catch (UrlConversionException e) {
												throw new RuntimeException("Failed to turn path '" + p.normalize() + "' into URL!", e);
											}
										}).collect(Collectors.toList())
						);
					}
				}
			}
		}
	}
}

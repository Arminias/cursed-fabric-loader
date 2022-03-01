/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.launch.knot;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.FabricLoaderImpl;
import net.fabricmc.loader.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.entrypoint.minecraft.hooks.EntrypointUtils;
import net.fabricmc.loader.game.GameProvider;
import net.fabricmc.loader.game.GameProviders;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import net.fabricmc.loader.launch.common.FabricMixinBootstrap;
import net.fabricmc.loader.util.SystemProperties;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import net.fabricmc.loader.util.mappings.TinyRemapperMappingsHelper;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import org.objectweb.asm.commons.Remapper;
import org.spongepowered.asm.launch.MixinBootstrap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public final class Knot extends FabricLauncherBase {
	public static final int RELOCATE_SRC = 1;
	protected Map<String, Object> properties = new HashMap<>();

	private KnotClassLoaderInterface classLoader;
	private boolean isDevelopment;
	private EnvType envType;
	private final File gameJarFile;
	private GameProvider provider;

	public Knot(EnvType type, File gameJarFile) {
		this.envType = type;
		this.gameJarFile = gameJarFile;
	}

	protected ClassLoader init(String[] args) {
		setProperties(properties);

		// configure fabric vars
		if (envType == null) {
			String side = System.getProperty(SystemProperties.SIDE);
			if (side == null) {
				throw new RuntimeException("Please specify side or use a dedicated Knot!");
			}

			switch (side.toLowerCase(Locale.ROOT)) {
			case "client":
				envType = EnvType.CLIENT;
				break;
			case "server":
				envType = EnvType.SERVER;
				break;
			default:
				throw new RuntimeException("Invalid side provided: must be \"client\" or \"server\"!");
			}
		}

		// TODO: Restore these undocumented features
		// String proposedEntrypoint = System.getProperty("fabric.loader.entrypoint");

		List<GameProvider> providers = GameProviders.create();
		provider = null;

		for (GameProvider p : providers) {
			if (p.locateGame(envType, args, this.getClass().getClassLoader())) {
				provider = p;
				break;
			}
		}

		if (provider != null) {
			LOGGER.info("Loading for game " + provider.getGameName() + " " + provider.getRawGameVersion());
		} else {
			LOGGER.error("Could not find valid game provider!");
			for (GameProvider p : providers) {
				LOGGER.error("- " + p.getGameName());
			}
			throw new RuntimeException("Could not find valid game provider!");
		}

		isDevelopment = Boolean.parseBoolean(System.getProperty(SystemProperties.DEVELOPMENT, "false"));

		// Setup classloader
		// TODO: Provide KnotCompatibilityClassLoader in non-exclusive-Fabric pre-1.13 environments?
		boolean useCompatibility = provider.requiresUrlClassLoader() || Boolean.parseBoolean(System.getProperty("fabric.loader.useCompatibilityClassLoader", "false"));
		classLoader = useCompatibility ? new KnotCompatibilityClassLoader(isDevelopment(), envType, provider) : new KnotClassLoader(isDevelopment(), envType, provider);
		ClassLoader cl = (ClassLoader) classLoader;

		if (provider.isObfuscated()) {
			for (Path path : provider.getGameContextJars()) {
				FabricLauncherBase.deobfuscate(
						provider.getGameId(), provider.getNormalizedGameVersion(),
						provider.getLaunchDirectory(),
						path,
						this
						);
			}
		}

		FabricLoaderImpl loader = FabricLoaderImpl.getInstance();
		loader.setGameProvider(provider);
		loader.loadCore();
		for (Path path : provider.getGameContextJars()) {
			for (ModContainer m : loader.coremods) {
				String sidedOrigin = getEnvironmentType().name().toLowerCase(Locale.ENGLISH);
				TinyRemapperWithOverwrites remapper = new TinyRemapperWithOverwrites(TinyRemapper.newRemapper()
						.withMappings(TinyRemapperMappingsHelper.create(
								getMappingConfiguration().getMappings(), getMappingConfiguration().getMappings().getMetadata().getNamespaces().contains(sidedOrigin)
										? sidedOrigin : "official", getMappingConfiguration().getTargetNamespace())
						)
						.rebuildSourceFilenames(true)
						.fixPackageAccess(!isDevelopment()).extraRemapper(new Remapper() {
							/**
							 * Maps the internal name of a class to its new name. The default implementation of this method
							 * returns the given name, unchanged. Subclasses can override.
							 *
							 * @param internalName the internal name of a class.
							 * @return the new internal name.
							 */
							@Override
							public String map(String internalName) {
								if (RELOCATE_SRC == 0)
									if (internalName.contains("/")) {
										return internalName.replace("net/minecraft/src/", "net/minecraft/");
									}
									else {
										return "net/minecraft/" + internalName;
									}
								else if (RELOCATE_SRC == 1)
									if (!internalName.contains("/")) {
										return "net/minecraft/src/" + internalName;
									}
								return internalName;
							}
						})
						.build()
				);
				try {
					LOGGER.info("Loading coremod... " + path.toString() + ", " + UrlUtil.asPath(m.getOriginUrl()).toAbsolutePath());
				} catch (UrlConversionException e) {
					e.printStackTrace();
				}
				String versionedId = provider.getNormalizedGameVersion().isEmpty() ? provider.getGameId() : String.format("%s-%s", provider.getGameId(), provider.getNormalizedGameVersion());
				Path jarPath = provider.getLaunchDirectory().resolve(".fabric").resolve("remappedJars").resolve(versionedId).resolve(getMappingConfiguration().getTargetNamespace() + "-" + path.getFileName());
				try (OutputConsumerPath o = new OutputConsumerPath.Builder(jarPath)
						// force jar despite the .tmp extension
						.assumeArchive(true)
						// don't accept class names from a blacklist of dependencies that Fabric itself utilizes
						// TODO: really could use a better solution, as always...
						.filter(clsName -> !clsName.startsWith("com/google/common/")
								&& !clsName.startsWith("com/google/gson/")
								&& !clsName.startsWith("com/google/thirdparty/")
								&& !clsName.startsWith("org/apache/logging/log4j/"))
						.build()) {
					remapper.readInputs(UrlUtil.asPath(m.getOriginUrl()));
					remapper.INSTANCE.apply(o);
				} catch (IOException | UrlConversionException e) {
					throw new RuntimeException("Failed to remap '" + m.getOriginUrl() + "'!", e);
				} finally {
					remapper.INSTANCE.finish();
				}
				try {
					propose(UrlUtil.asUrl(jarPath));
				} catch (UrlConversionException e) {
					e.printStackTrace();
				}
			}
		}

		LOGGER.info("Coremod loading done!");

		// Locate entrypoints before switching class loaders
		provider.getEntrypointTransformer().locateEntrypoints(this);

		Thread.currentThread().setContextClassLoader(cl);

		loader.load();

		loader.freeze();

		loader.loadAccessWideners();

		MixinBootstrap.init();
		FabricMixinBootstrap.init(getEnvironmentType(), loader);
		FabricLauncherBase.finishMixinBootstrapping();

		classLoader.getDelegate().initializeTransformers();

		EntrypointUtils.invoke("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);

		return cl;
	}

	public void launch(ClassLoader cl) {
		if(this.provider == null) {
			throw new IllegalStateException("Game provider was not initialized! (Knot#init(String[]))");
		}
		provider.launch(cl);
	}

	@Override
	public String getTargetNamespace() {
		// TODO: Won't work outside of Yarn
		return isDevelopment ? "named" : "intermediary";
	}

	@Override
	public Collection<URL> getLoadTimeDependencies() {
		String cmdLineClasspath = System.getProperty("java.class.path");

		return Arrays.stream(cmdLineClasspath.split(File.pathSeparator)).filter((s) -> {
			if (s.equals("*") || s.endsWith(File.separator + "*")) {
				System.err.println("WARNING: Knot does not support wildcard classpath entries: " + s + " - the game may not load properly!");
				return false;
			} else {
				return true;
			}
		}).map((s) -> {
			File file = new File(s);
			if (!file.equals(gameJarFile)) {
				try {
					return (UrlUtil.asUrl(file));
				} catch (UrlConversionException e) {
					LOGGER.debug(e);
					return null;
				}
			} else {
				return null;
			}
		}).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	@Override
	public void propose(URL url) {
		FabricLauncherBase.LOGGER.debug("[Knot] Proposed " + url + " to classpath.");
		classLoader.addURL(url);
	}

	@Override
	public EnvType getEnvironmentType() {
		return envType;
	}

	@Override
	public boolean isClassLoaded(String name) {
		return classLoader.isClassLoaded(name);
	}

	@Override
	public InputStream getResourceAsStream(String name) {
		try {
			return classLoader.getResourceAsStream(name, false);
		} catch (IOException e) {
			throw new RuntimeException("Failed to read file '" + name + "'!", e);
		}
	}

	@Override
	public ClassLoader getTargetClassLoader() {
		return (ClassLoader) classLoader;
	}

	@Override
	public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
		if (runTransformers) {
			return classLoader.getDelegate().getPreMixinClassByteArray(name, false);
		} else {
			return classLoader.getDelegate().getRawClassByteArray(name, false);
		}
	}

	@Override
	public boolean isDevelopment() {
		return isDevelopment;
	}

	@Override
	public String getEntrypoint() {
		return provider.getEntrypoint();
	}

	public static void main(String[] args) {
		new Knot(null, null).init(args);
	}
}

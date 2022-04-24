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

package net.fabricmc.loader.launch.common;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.launch.knot.Knot;
import net.fabricmc.loader.util.Arguments;
import net.fabricmc.loader.util.UrlConversionException;
import net.fabricmc.loader.util.UrlUtil;
import net.fabricmc.loader.util.mappings.TinyRemapperMappingsHelper;
import net.fabricmc.mapping.tree.TinyTree;
import net.fabricmc.tinyremapper.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.commons.Remapper;
import org.spongepowered.asm.mixin.MixinEnvironment;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public abstract class FabricLauncherBase implements FabricLauncher {
	public static Path minecraftJar;

	protected static Logger LOGGER = LogManager.getFormatterLogger("FabricLoader");
	private static boolean mixinReady;
	private static Map<String, Object> properties;
	private static FabricLauncher launcher;
	private static MappingConfiguration mappingConfiguration = new MappingConfiguration();

	protected FabricLauncherBase() {
		setLauncher(this);
	}

	public static File getLaunchDirectory(Arguments argMap) {
		return new File(argMap.getOrDefault("gameDir", "."));
	}

	public static Class<?> getClass(String className) throws ClassNotFoundException {
		return Class.forName(className, true, getLauncher().getTargetClassLoader());
	}

	public static class TinyRemapperWithOverwrites {
		public static TinyRemapper INSTANCE;
		public static Map<String, AccessibleObject> accessMap;
		private static Map<String, ClassInstance> intermediaryMap = new HashMap<>();
		public TinyRemapperWithOverwrites(TinyRemapper t) {
			INSTANCE = t;
			accessMap = new HashMap<>();
			// Fields
			Field[] fields = null;

			try {
				fields = new Field[]{
						TinyRemapper.class.getDeclaredField("singleInputTags"),
						TinyRemapper.class.getDeclaredField("dirty"),
						TinyRemapper.class.getDeclaredField("readClasses"),
						TinyRemapper.class.getDeclaredField("classesToMakePublic"),
						TinyRemapper.class.getDeclaredField("membersToMakePublic"),
						ClassInstance.class.getDeclaredField("isInput"),
						ClassInstance.class.getDeclaredField("srcPath"),
						ClassInstance.class.getDeclaredField("name"),
						ClassInstance.class.getDeclaredField("data")
				};
			} catch (NoSuchFieldException e) {
				e.printStackTrace();
			}
			for (Field f : fields) {
				f.setAccessible(true);
				//LOGGER.info(f.getName());
				accessMap.put("field_"+f.getName(), f);
			}
			Method[] methods = null;

			try {
				methods = new Method[]{
						TinyRemapper.class.getDeclaredMethod("read",
								Path.class, boolean.class, InputTag[].class, boolean.class, List.class),
						TinyRemapper.class.getDeclaredMethod("mapClass", String.class),
						ClassInstance.class.getDeclaredMethod("addInputTags", InputTag[].class),
						ClassInstance.class.getDeclaredMethod("getInputTags")
				};
			} catch (NoSuchMethodException e) {
				e.printStackTrace();
			}
			for (Method m : methods) {
				m.setAccessible(true);
				//LOGGER.info(m.getName());
				accessMap.put("method_"+m.getName(), m);
			}
		}
		public void readInputs(final Path... inputs) {
			readInputs(null, inputs);
		}

		public void readInputs(InputTag tag, Path... inputs) {
			read(inputs, true, tag);
		}

		private static void makeMembersPublic(ClassInstance cls) {
			try {
				Set<MemberInstance> membersToMakePublic = (Set<MemberInstance>) ((Field) accessMap.get("field_membersToMakePublic")).get(INSTANCE);
				for (MemberInstance member : cls.getMembers()) {
					membersToMakePublic.add(member);
				}
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
		}

		public List<ClassInstance> read(Path[] inputs, boolean isInput, InputTag tag) {
			InputTag[] tags = new InputTag[0];
			try {
				tags = ((AtomicReference<Map<InputTag, InputTag[]>>)((Field)accessMap.get("field_singleInputTags")).get(INSTANCE)).get().get(tag);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			List<List<ClassInstance>> futures = new ArrayList<>();
			List<FileSystem> fsToClose = new ArrayList<>();

			for (Path input : inputs) {
				try {
					/*futures.addAll(((Collection<? extends CompletableFuture<List<ClassInstance>>>)
							((Method)accessMap.get("method_read"))
							.invoke(INSTANCE, input, isInput, tags, true, fsToClose))
					.forEach((x) ->
					{}));*/

					Collection<? extends CompletableFuture<List<ClassInstance>>> x =
							(Collection<? extends CompletableFuture<List<ClassInstance>>>)
							((Method)accessMap.get("method_read"))
									.invoke(INSTANCE, input, isInput, tags, true, fsToClose);

					for (CompletableFuture<List<ClassInstance>> listCompletableFuture : x) {
						futures.add(listCompletableFuture.get());
					}

				} catch (IllegalAccessException | InvocationTargetException | InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
			}

			List<ClassInstance> ret;

			if (futures.isEmpty()) {
				return Collections.emptyList();
			} else if (futures.size() == 1) {
				ret = futures.get(0);
			} else {
				ret = futures.stream().flatMap(Collection::stream).collect(Collectors.toList());
			}

			try {
				((Field)accessMap.get("field_dirty")).set(INSTANCE, true);
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			Field readClasses = ((Field)accessMap.get("field_readClasses"));
			ret.sort((o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName()));
			for (int i = 0; i < ret.size(); i++) {
				ClassInstance res = ret.get(i);
				for (FileSystem fs : fsToClose) {
					try {
						FileSystemHandler.close(fs);
					} catch (Exception ignored) {
					}
				}

				try {
					addClass(res, (Map<String, ClassInstance>) readClasses.get(INSTANCE), intermediaryMap);
				} catch (IllegalAccessException | InvocationTargetException e) {
					e.printStackTrace();
				}
			}
			return ret;
		}

		private static void addClass(ClassInstance cls, Map<String, ClassInstance> out, Map<String, ClassInstance> intermediary) throws IllegalAccessException, InvocationTargetException {
			Field isInput = (Field) accessMap.get("field_isInput");
			Field srcPath = (Field) accessMap.get("field_srcPath");
			Field classesToMakePublic = (Field) accessMap.get("field_classesToMakePublic");
			Field field_name = (Field) accessMap.get("field_name");
			Method addInputTags = (Method) accessMap.get("method_addInputTags");
			Method getInputTags = (Method) accessMap.get("method_getInputTags");
			Method mapClass = (Method) accessMap.get("method_mapClass");

			String name = cls.getName();
			String mapped_name = (String) mapClass.invoke(INSTANCE, cls.getName());
			// add new class or replace non-input class with input class, warn if two input classes clash
			for (;;) {
				ClassInstance prev = out.putIfAbsent(name, cls);
				ClassInstance prev_mapped = out.putIfAbsent(mapped_name, cls);
				if (prev == null && prev_mapped == null) {
					((Set<ClassInstance>)classesToMakePublic.get(INSTANCE)).add(cls);
					makeMembersPublic(cls);
					return;
				}
				if (prev != null) {
					if ((boolean) isInput.get(cls)) {
						if ((boolean) isInput.get(prev)) {
							//LOGGER.debug("duplicate input class %s, from %s and %s", name, srcPath.get(prev), srcPath.get(cls));
							addInputTags.invoke(prev, getInputTags.invoke(cls));
							((Set<ClassInstance>)classesToMakePublic.get(INSTANCE)).add(prev);
							makeMembersPublic(prev);
							return;
						} else if (out.replace(name, prev, cls)) { // cas with retry-loop on failure
							addInputTags.invoke(cls, getInputTags.invoke(prev));
							((Set<ClassInstance>)classesToMakePublic.get(INSTANCE)).add(cls);
							makeMembersPublic(cls);
							return;
						}
					} else {
						addInputTags.invoke(prev, getInputTags.invoke(cls));
						((Set<ClassInstance>)classesToMakePublic.get(INSTANCE)).add(prev);
						makeMembersPublic(prev);
						return;
					}
				}
				else {
					if ((boolean) isInput.get(cls)) {
						if ((boolean) isInput.get(prev_mapped)) {
							addInputTags.invoke(prev_mapped, getInputTags.invoke(cls));
							((Set<ClassInstance>)classesToMakePublic.get(INSTANCE)).add(prev_mapped);
							makeMembersPublic(prev_mapped);
							return;
						} else if (out.replace(name, prev_mapped, cls)) { // cas with retry-loop on failure
							addInputTags.invoke(cls, getInputTags.invoke(prev_mapped));
							((Set<ClassInstance>)classesToMakePublic.get(INSTANCE)).add(cls);
							makeMembersPublic(cls);
							return;
						}
					} else {
						addInputTags.invoke(prev_mapped, getInputTags.invoke(cls));
						((Set<ClassInstance>)classesToMakePublic.get(INSTANCE)).add(prev_mapped);
						makeMembersPublic(prev_mapped);
						return;
					}
				}
			}
		}

	}

	@Override
	public MappingConfiguration getMappingConfiguration() {
		return mappingConfiguration;
	}

	private static boolean emittedInfo = false;

	protected static Path deobfuscate(String gameId, String gameVersion, Path gameDir, Path jarFile, FabricLauncher launcher) {
		return deobfuscate_backend(gameId, gameVersion, gameDir, jarFile, launcher);
	}

	private static Path deobfuscate_backend(String gameId, String gameVersion, Path gameDir, Path jarFile, FabricLauncher launcher, String... namespaces) {
		Path resultJarFile = jarFile;

		LOGGER.debug("Requesting deobfuscation of " + jarFile.getFileName());

		TinyTree mappings = launcher.isDevelopment() ? null : mappingConfiguration.getMappings();
		String targetNamespace = namespaces.length == 0 ? mappingConfiguration.getTargetNamespace() : namespaces[0];

		if (mappings != null && mappings.getMetadata().getNamespaces().contains(targetNamespace)) {
			LOGGER.debug("Fabric mapping file detected, applying...");

			try {
				if (!Files.exists(jarFile)) {
					throw new RuntimeException("Could not locate Minecraft: " + jarFile + " not found");
				}

				Path deobfJarDir = gameDir.resolve(".fabric").resolve("remappedJars");

				if (!gameId.isEmpty()) {
					String versionedId = gameVersion.isEmpty() ? gameId : String.format("%s-%s", gameId, gameVersion);
					deobfJarDir = deobfJarDir.resolve(versionedId);
				}

				if (!Files.exists(deobfJarDir)) {
					Files.createDirectories(deobfJarDir);
				}

				// TODO: allow versioning mappings?
				String deobfJarFilename = mappingConfiguration.getTargetNamespace() + "-" + jarFile.getFileName();
				if (namespaces.length != 0) deobfJarFilename += ".remap";
				Path deobfJarFile = deobfJarDir.resolve(deobfJarFilename);
				Path deobfJarFileTmp = deobfJarDir.resolve(deobfJarFilename + ".tmp");
				Files.deleteIfExists(deobfJarFile);

				if (Files.exists(deobfJarFileTmp)) {
					LOGGER.warn("Incomplete remapped file found! This means that the remapping process failed on the previous launch. If this persists, make sure to let us at Fabric know!");
					Files.deleteIfExists(deobfJarFileTmp);
					Files.deleteIfExists(deobfJarFile);
				}
				if (!Files.exists(deobfJarFile)) {
					boolean found = false;
					while (!found) {
						if (!emittedInfo) {
							LOGGER.info("Fabric is preparing JARs on first launch, this may take a few seconds...");
							emittedInfo = true;
						}

						String sidedOrigin = launcher.getEnvironmentType().name().toLowerCase(Locale.ENGLISH);
						String originNamespace = namespaces.length == 0 ? (mappings.getMetadata().getNamespaces().contains(sidedOrigin)
								? sidedOrigin : "official") : namespaces[1];
						TinyRemapperWithOverwrites remapper = new TinyRemapperWithOverwrites(TinyRemapper.newRemapper()
							.withMappings(TinyRemapperMappingsHelper.create(mappings, originNamespace, targetNamespace))
							.rebuildSourceFilenames(true)
							.fixPackageAccess(!launcher.isDevelopment())
							.extraRemapper(new Remapper() {
								/**
								 * Maps the internal name of a class to its new name. The default implementation of this method
								 * returns the given name, unchanged. Subclasses can override.
								 *
								 * @param internalName the internal name of a class.
								 * @return the new internal name.
								 */
								@Override
								public String map(String internalName) {
									if (Knot.RELOCATE_SRC == 0)
										if (internalName.contains("/")) {
											return internalName.replace("net/minecraft/src/", "net/minecraft/");
										}
										else {
											return "net/minecraft/" + internalName;
										}
									else if (Knot.RELOCATE_SRC == 1)
										if (!internalName.contains("/")) {
											return "net/minecraft/src/" + internalName;
										}
									return internalName;
								}
							})
							.build()
							);

						Set<Path> depPaths = new HashSet<>();
						for (URL url : launcher.getLoadTimeDependencies()) {
							try {
								Path path = UrlUtil.asPath(url);
								if (!Files.exists(path)) {
									throw new RuntimeException("Path does not exist: " + path);
								}

								if (!path.equals(jarFile)) {
									depPaths.add(path);
								}
							} catch (UrlConversionException e) {
								throw new RuntimeException("Failed to convert '" + url + "' to path!", e);
							}
						}

						try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(deobfJarFileTmp)
								// force jar despite the .tmp extension
								.assumeArchive(true)
								// don't accept class names from a blacklist of dependencies that Fabric itself utilizes
								// TODO: really could use a better solution, as always...
								.filter(clsName -> !clsName.startsWith("com/google/common/")
										&& !clsName.startsWith("com/google/gson/")
										&& !clsName.startsWith("com/google/thirdparty/")
										&& !clsName.startsWith("org/apache/logging/log4j/"))
								.build()) {
							for (Path path : depPaths) {
								LOGGER.debug("Appending '" + path + "' to remapper classpath");
								remapper.INSTANCE.readClassPath(path);
							}
							remapper.readInputs(jarFile);
							remapper.INSTANCE.apply(outputConsumer);
						} catch (IOException e) {
							throw new RuntimeException("Failed to remap '" + jarFile + "'!", e);
						} finally {
							remapper.INSTANCE.finish();
						}

						// Minecraft doesn't tend to check if a ZipFileSystem is already present,
						// so we clean up here.

						depPaths.add(deobfJarFileTmp);
						for (Path p : depPaths) {
							try {
								p.getFileSystem().close();
							} catch (Exception e) {
								// pass
							}

							try {
								FileSystems.getFileSystem(new URI("jar:" + p.toUri())).close();
							} catch (Exception e) {
								// pass
							}
						}

						try (JarFile jar = new JarFile(deobfJarFileTmp.toFile())) {
							found = jar.stream().anyMatch((e) -> e.getName().endsWith(".class"));
						}

						if (!found) {
							LOGGER.error("Generated deobfuscated JAR contains no classes! Trying again...");
							Files.delete(deobfJarFileTmp);
						} else {
							Files.move(deobfJarFileTmp, deobfJarFile);
						}
					}
				}

				if (!Files.exists(deobfJarFile)) {
					throw new RuntimeException("Remapped .JAR file does not exist after remapping! Cannot continue!");
				}

				resultJarFile = deobfJarFile;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		try {
			launcher.propose(UrlUtil.asUrl(resultJarFile));
		} catch (UrlConversionException e) {
			throw new RuntimeException(e);
		}

		if (minecraftJar == null) {
			minecraftJar = resultJarFile;
		}

		return resultJarFile;
	}

	public static void processArgumentMap(Arguments argMap, EnvType envType) {
		switch (envType) {
			case CLIENT:
				if (!argMap.containsKey("accessToken")) {
					argMap.put("accessToken", "FabricMC");
				}

				if (!argMap.containsKey("version")) {
					argMap.put("version", "Fabric");
				}

				String versionType = "";
				if(argMap.containsKey("versionType") && !argMap.get("versionType").equalsIgnoreCase("release")){
					versionType = argMap.get("versionType") + "/";
				}
				argMap.put("versionType", versionType + "Fabric");

				if (!argMap.containsKey("gameDir")) {
					argMap.put("gameDir", getLaunchDirectory(argMap).getAbsolutePath());
				}
				break;
			case SERVER:
				argMap.remove("version");
				argMap.remove("gameDir");
				argMap.remove("assetsDir");
				break;
		}
	}

	protected static void setProperties(Map<String, Object> propertiesA) {
		if (properties != null && properties != propertiesA) {
			throw new RuntimeException("Duplicate setProperties call!");
		}

		properties = propertiesA;
	}

	private static void setLauncher(FabricLauncher launcherA) {
		if (launcher != null && launcher != launcherA) {
			throw new RuntimeException("Duplicate setLauncher call!");
		}

		launcher = launcherA;
	}

	public static FabricLauncher getLauncher() {
		return launcher;
	}

	public static Map<String, Object> getProperties() {
		return properties;
	}

	protected static void finishMixinBootstrapping() {
		if (mixinReady) {
			throw new RuntimeException("Must not call FabricLauncherBase.finishMixinBootstrapping() twice!");
		}

		try {
			Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
			m.setAccessible(true);
			m.invoke(null, MixinEnvironment.Phase.INIT);
			m.invoke(null, MixinEnvironment.Phase.DEFAULT);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		mixinReady = true;
	}

	public static boolean isMixinReady() {
		return mixinReady;
	}
}

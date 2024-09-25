package net.minecraftforge.gradle.dev;

import net.minecraftforge.gradle.ArchiveTaskHelper;
import net.minecraftforge.gradle.CopyInto;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.tasks.*;
import net.minecraftforge.gradle.tasks.abstractutil.DelayedJar;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.abstractutil.FileFilterTask;
import net.minecraftforge.gradle.tasks.dev.*;
import org.gradle.api.Action;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static net.minecraftforge.gradle.dev.DevConstants.*;

public class CauldronDevPlugin extends DevBasePlugin {
    @Override
    public void applyPlugin() {
        super.applyPlugin();

        // set folders
        getExtension().setFmlDir("forge/fml");
        getExtension().setForgeDir("forge");
        getExtension().setBukkitDir("bukkit");

        /* Not needed for anything and is broken. **
        // configure genSrg task.
        GenSrgTask genSrgTask = (GenSrgTask) project.getTasks().getByName("genSrgs");
        {
            String[] paths = {DevConstants.FML_RESOURCES, DevConstants.FORGE_RESOURCES, DevConstants.EXTRA_RESOURCES};
            for (String path : paths)
            {
                for (File f : project.fileTree(delayedFile(path).call()).getFiles())
                {
                    if(f.getPath().endsWith(".exc"))
                        genSrgTask.addExtraExc(f);
                    else if(f.getPath().endsWith(".srg"))
                        genSrgTask.addExtraSrg(f);
                }
            }
        }
        */

        createJarProcessTasks();
        createProjectTasks();
        createEclipseTasks();
        createMiscTasks();
        createSourceCopyTasks();
        createPackageTasks();

        // the master setup task.
        makeTask("setupCauldron", task -> {
            task.dependsOn("extractCauldronSources", "generateProjects", EclipsePlugin.ECLIPSE_TASK_NAME, "copyAssets");
            task.setGroup("Cauldron");
        });

        // clean packages
        makeTask("cleanPackages", Delete.class, task -> task.delete("build/distributions"));

        // the master task.
        makeTask("buildPackages", task -> {
            //task.dependsOn("launch4j", "createChangelog", "packageUniversal", "packageInstaller", "genJavadocs");
            task.dependsOn("cleanPackages", "createChangelog", "packageUniversal", "packageInstaller");
            task.setGroup("Cauldron");
        });
    }

    @Override
    protected final DelayedFile getDevJson() {
        return delayedFile(DevConstants.EXTRA_JSON_DEV);
    }

    protected void createJarProcessTasks() {
        makeTask("deobfuscateJar", ProcessJarTask.class, task -> {
            task.setInJar(delayedFile(Constants.JAR_MERGED));
            task.setOutCleanJar(delayedFile(JAR_SRG_CDN));
            task.setSrg(delayedFile(JOINED_SRG));
            task.setExceptorCfg(delayedFile(JOINED_EXC));
            task.setExceptorJson(delayedFile(EXC_JSON));
            task.addTransformerClean(delayedFile(FML_RESOURCES + "/fml_at.cfg"));
            task.addTransformerClean(delayedFile(FORGE_RESOURCES + "/forge_at.cfg"));
            task.setApplyMarkers(true);
            task.dependsOn("downloadMcpTools", "mergeJars");
        });

        makeTask("decompile", DecompileTask.class, task -> {
            task.setInJar(delayedFile(JAR_SRG_CDN));
            task.setOutJar(delayedFile(ZIP_DECOMP_CDN));
            task.setFernFlower(delayedFile(Constants.FERNFLOWER));
            task.setPatch(delayedFile(MCP_PATCH_DIR));
            task.setAstyleConfig(delayedFile(ASTYLE_CFG));
            task.dependsOn("downloadMcpTools", "deobfuscateJar");
        });

        makeTask("forgePatchJar", ProcessSrcJarTask.class, task -> {
            task.setInJar(delayedFile(ZIP_DECOMP_CDN));
            task.setOutJar(delayedFile(ZIP_FORGED_CDN));
            task.addStage("fml", delayedFile(FML_PATCH_DIR), delayedFile(FML_SOURCES), delayedFile(FML_RESOURCES), delayedFile("{FML_CONF_DIR}/patches/Start.java"), delayedFile(DEOBF_DATA), delayedFile(FML_VERSION));
            task.addStage("forge", delayedFile(FORGE_PATCH_DIR), delayedFile(FORGE_SOURCES), delayedFile(FORGE_RESOURCES));
            task.addStage("bukkit", null, delayedFile(BUKKIT_SOURCES));
            task.setDoesCache(false);
            task.setMaxFuzz(2);
            task.dependsOn("decompile", "compressDeobfData", "createVersionPropertiesFML");
        });

        makeTask("remapCleanJar", RemapSourcesTask.class, task -> {
            task.setInJar(delayedFile(ZIP_FORGED_CDN));
            task.setOutJar(delayedFile(REMAPPED_CLEAN));
            task.setMethodsCsv(delayedFile(METHODS_CSV));
            task.setFieldsCsv(delayedFile(FIELDS_CSV));
            task.setParamsCsv(delayedFile(PARAMS_CSV));
            task.setDoesCache(true);
            task.setNoJavadocs();
            task.dependsOn("forgePatchJar");
        });

        makeTask("cauldronPatchJar", ProcessSrcJarTask.class, task -> {
            //task.setInJar(delayedFile(ZIP_FORGED_CDN)); UNCOMMENT FOR SRG NAMES
            task.setInJar(delayedFile(REMAPPED_CLEAN));
            task.setOutJar(delayedFile(ZIP_PATCHED_CDN));
            task.addStage("Cauldron", delayedFile(EXTRA_PATCH_DIR));
            task.setDoesCache(false);
            task.setMaxFuzz(2);
            task.dependsOn("forgePatchJar", "remapCleanJar");
        });

        makeTask("remapCauldronJar", RemapSourcesTask.class, task -> {
            task.setInJar(delayedFile(ZIP_PATCHED_CDN));
            task.setOutJar(delayedFile(ZIP_RENAMED_CDN));
            task.setMethodsCsv(delayedFile(METHODS_CSV));
            task.setFieldsCsv(delayedFile(FIELDS_CSV));
            task.setParamsCsv(delayedFile(PARAMS_CSV));
            task.setDoesCache(true);
            task.setNoJavadocs();
            task.dependsOn("cauldronPatchJar");
        });
    }

    private void createSourceCopyTasks() {
        makeTask("extractCleanResources", ExtractTask.class, task -> {
            task.exclude(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(REMAPPED_CLEAN));
            task.into(delayedFile(ECLIPSE_CLEAN_RES));
            task.dependsOn("extractWorkspace", "remapCleanJar");
        });

        makeTask("extractCleanSource", ExtractTask.class, task -> {
            task.include(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(REMAPPED_CLEAN));
            task.into(delayedFile(ECLIPSE_CLEAN_SRC));
            task.dependsOn("extractCleanResources");
        });

        makeTask("extractCauldronResources", ExtractTask.class, task -> {
            task.exclude(JAVA_FILES);
            task.from(delayedFile(ZIP_RENAMED_CDN));
            task.into(delayedFile(ECLIPSE_CDN_RES));
            task.dependsOn("remapCauldronJar", "extractWorkspace");
            task.onlyIf(arg0 -> {
                File dir = delayedFile(ECLIPSE_CDN_RES).call();
                if (!dir.exists())
                    return true;

                ConfigurableFileTree tree = project.fileTree(dir);
                tree.include("**/*.java");

                return !tree.isEmpty();
            });
        });

        makeTask("extractCauldronSources", ExtractTask.class, task -> {
            task.include(JAVA_FILES);
            task.from(delayedFile(ZIP_RENAMED_CDN));
            task.into(delayedFile(ECLIPSE_CDN_SRC));
            task.dependsOn("extractCauldronResources");
            task.onlyIf(arg0 -> {
                File dir = delayedFile(ECLIPSE_CDN_SRC).call();
                if (!dir.exists())
                    return true;

                ConfigurableFileTree tree = project.fileTree(dir);
                tree.include("**/*.java");

                return !tree.isEmpty();
            });
        });
    }

    private void createProjectTasks() {
        makeTask("createVersionPropertiesFML", FMLVersionPropTask.class, task -> {
            //task.setTasks("createVersionProperties");
            //task.setBuildFile(delayedFile("{FML_DIR}/build.gradle"));
            task.setVersion(() -> FmlDevPlugin.getVersionFromGit(project, new File(delayedString("{FML_DIR}").call())));
            task.setOutputFile(delayedFile(FML_VERSION));
        });

        makeTask("extractRes", ExtractTask.class, task -> {
            task.into(delayedFile(EXTRACTED_RES));
            for (File f : delayedFile("src/main").call().listFiles()) {
                if (f.isDirectory())
                    continue;
                String path = f.getAbsolutePath();
                if (path.endsWith(".jar") || path.endsWith(".zip"))
                    task.from(delayedFile(path));
            }
        });

        makeTask("generateProjectClean", GenDevProjectsTask.class, task -> {
            task.setTargetDir(delayedFile(ECLIPSE_CLEAN));
            task.setJson(delayedFile(EXTRA_JSON_DEV)); // Change to FmlConstants.JSON_BASE eventually, so that it's the base vanilla json
            task.addSource(delayedFile(ECLIPSE_CLEAN_SRC));
            task.addResource(delayedFile(ECLIPSE_CLEAN_RES));

            task.setMcVersion(delayedString("{MC_VERSION}"));
            task.setMappingChannel(delayedString("{MAPPING_CHANNEL}"));
            task.setMappingVersion(delayedString("{MAPPING_VERSION}"));

            task.dependsOn("extractNatives");
        });

        makeTask("generateProjectCauldron", GenDevProjectsTask.class, task -> {
            task.setJson(delayedFile(EXTRA_JSON_DEV));
            task.setTargetDir(delayedFile(ECLIPSE_CDN));

            task.addSource(delayedFile(ECLIPSE_CDN_SRC));
            task.addSource(delayedFile(EXTRA_SOURCES));
            task.addTestSource(delayedFile(EXTRA_TEST_SOURCES));

            task.addResource(delayedFile(ECLIPSE_CDN_RES));
            task.addResource(delayedFile(EXTRA_RESOURCES));
            task.addResource(delayedFile(EXTRACTED_RES));
            task.addTestSource(delayedFile(EXTRA_TEST_SOURCES));

            task.setMcVersion(delayedString("{MC_VERSION}"));
            task.setMappingChannel(delayedString("{MAPPING_CHANNEL}"));
            task.setMappingVersion(delayedString("{MAPPING_VERSION}"));

            task.dependsOn("extractRes", "extractNatives", "createVersionPropertiesFML");
        });

        makeTask("generateProjects", task -> task.dependsOn("generateProjectClean", "generateProjectCauldron"));
    }

    private void createEclipseTasks() {
        makeTask("eclipseClean", SubprojectTask.class, task -> {
            task.setBuildFile(delayedFile(ECLIPSE_CLEAN + "/build.gradle"));
            task.setTasks(EclipsePlugin.ECLIPSE_TASK_NAME);
            task.dependsOn("extractCleanSource", "generateProjects");
        });

        makeTask("eclipseCauldron", SubprojectTask.class, task -> {
            task.setBuildFile(delayedFile(ECLIPSE_CDN + "/build.gradle"));
            task.setTasks(EclipsePlugin.ECLIPSE_TASK_NAME);
            task.dependsOn("extractCauldronSources", "generateProjects");
        });

        makeTask(EclipsePlugin.ECLIPSE_TASK_NAME, task -> task.dependsOn("eclipseClean", "eclipseCauldron"));
    }

    private void createMiscTasks() {
        DelayedFile rangeMapClean = delayedFile("{BUILD_DIR}/tmp/rangemapCLEAN.txt");
        DelayedFile rangeMapDirty = delayedFile("{BUILD_DIR}/tmp/rangemapDIRTY.txt");

        makeTask("extractRangeCauldron", ExtractS2SRangeTask.class, task -> {
            task.setLibsFromProject(delayedFile(ECLIPSE_CDN + "/build.gradle"), CONFIG_COMPILE, true);
            task.addIn(delayedFile(ECLIPSE_CDN_SRC));
            task.setRangeMap(rangeMapDirty);
        });

        makeTask("retroMapCauldron", ApplyS2STask.class, task -> {
            task.addIn(delayedFile(ECLIPSE_CDN_SRC));
            task.setOut(delayedFile(PATCH_DIRTY));
            task.addSrg(delayedFile(MCP_2_SRG_SRG));
            task.addExc(delayedFile(MCP_EXC));
            task.addExc(delayedFile(SRG_EXC)); // just in case
            task.setRangeMap(rangeMapDirty);
            task.dependsOn("genSrgs", "extractRangeCauldron");
            String[] paths = {DevConstants.FML_RESOURCES, DevConstants.FORGE_RESOURCES, DevConstants.EXTRA_RESOURCES};
            for (String path : paths) {
                for (File f : project.fileTree(delayedFile(path).call()).getFiles()) {
                    if (f.getPath().endsWith(".exc"))
                        task.addExc(f);
                    else if (f.getPath().endsWith(".srg"))
                        task.addSrg(f);
                }
            }
        });

        makeTask("extractRangeClean", ExtractS2SRangeTask.class, task -> {
            task.setLibsFromProject(delayedFile(ECLIPSE_CLEAN + "/build.gradle"), CONFIG_COMPILE, true);
            task.addIn(delayedFile(REMAPPED_CLEAN));
            task.setRangeMap(rangeMapClean);
        });

        makeTask("retroMapClean", ApplyS2STask.class, task -> {
            task.addIn(delayedFile(REMAPPED_CLEAN));
            task.setOut(delayedFile(PATCH_CLEAN));
            task.addSrg(delayedFile(MCP_2_SRG_SRG));
            task.addExc(delayedFile(MCP_EXC));
            task.addExc(delayedFile(SRG_EXC)); // just in case
            task.setRangeMap(rangeMapClean);
            task.dependsOn("genSrgs", "extractRangeClean");
        });

        makeTask("genPatches", GeneratePatches.class, task -> {
            task.setPatchDir(delayedFile(EXTRA_PATCH_DIR));
            task.setOriginal(delayedFile(ECLIPSE_CLEAN_SRC));
            task.setChanged(delayedFile(ECLIPSE_CDN_SRC));
            task.setOriginalPrefix("../src-base/minecraft");
            task.setChangedPrefix("../src-work/minecraft");
            task.getTaskDependencies().getDependencies(task).clear(); // remove all the old dependants.
            task.setGroup("Cauldron");

            if (false) { // COMMENT OUT SRG PATCHES!
                task.setPatchDir(delayedFile(EXTRA_PATCH_DIR));
                task.setOriginal(delayedFile(PATCH_CLEAN)); // was ECLIPSE_CLEAN_SRC
                task.setChanged(delayedFile(PATCH_DIRTY)); // ECLIPSE_FORGE_SRC
                task.setOriginalPrefix("../src-base/minecraft");
                task.setChangedPrefix("../src-work/minecraft");
                task.dependsOn("retroMapCauldron", "retroMapClean");
                task.setGroup("Cauldron");
            }
        });

        makeTask("cleanCauldron", Delete.class, task -> {
            task.delete("eclipse");
            task.setGroup("Clean");
        });

        configureTask(LifecycleBasePlugin.CLEAN_TASK_NAME, task -> task.dependsOn("cleanCauldron"));

        makeTask("obfuscateJar", ObfuscateTask.class, task -> {
            task.setSrg(delayedFile(MCP_2_NOTCH_SRG));
            task.setExc(delayedFile(JOINED_EXC));
            task.setReverse(false);
            task.setPreFFJar(delayedFile(JAR_SRG_CDN));
            task.setOutJar(delayedFile(REOBF_TMP));
            task.setBuildFile(delayedFile(ECLIPSE_CDN + "/build.gradle"));
            task.setMethodsCsv(delayedFile(METHODS_CSV));
            task.setFieldsCsv(delayedFile(FIELDS_CSV));
            task.dependsOn("genSrgs");
        });

        makeTask("genBinPatches", GenBinaryPatches.class, task -> {
            task.setCleanClient(delayedFile(Constants.JAR_CLIENT_FRESH));
            task.setCleanServer(delayedFile(Constants.JAR_SERVER_FRESH));
            task.setCleanMerged(delayedFile(Constants.JAR_MERGED));
            task.setDirtyJar(delayedFile(REOBF_TMP));
            task.setDeobfDataLzma(delayedFile(DEOBF_DATA));
            task.setOutJar(delayedFile(BINPATCH_TMP));
            task.setSrg(delayedFile(JOINED_SRG));
            task.addPatchList(delayedFileTree(EXTRA_PATCH_DIR));
            task.addPatchList(delayedFileTree(FORGE_PATCH_DIR));
            task.addPatchList(delayedFileTree(FML_PATCH_DIR));
            task.dependsOn("obfuscateJar", "compressDeobfData");
        });

        /*
        makeTask("ciWriteBuildNumber", ForgeVersionReplaceTask.class, task -> {
            task.getOutputs().upToDateWhen(Constants.CALL_FALSE);
            task.setOutputFile(delayedFile(FORGE_VERSION_JAVA));
            task.setReplacement(delayedString("{BUILD_NUM}"));
        });

        makeTask("fmlChangelog", SubmoduleChangelogTask.class, task -> {
            task.setSubmodule(delayedFile("fml"));
            task.setModuleName("FML");
            task.setPrefix("MinecraftForge/FML");
        });
        */
    }

    private void createPackageTasks() {
        makeTask("createChangelog", ChangelogTask.class, task -> {
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.setServerRoot(delayedString("{JENKINS_SERVER}"));
            task.setJobName(delayedString("{JENKINS_JOB}"));
            task.setAuthName(delayedString("{JENKINS_AUTH_NAME}"));
            task.setAuthPassword(delayedString("{JENKINS_AUTH_PASSWORD}"));
            task.setTargetBuild(delayedString("{BUILD_NUM}"));
            task.setOutput(delayedFile(CHANGELOG));
        });

        /*
        makeTask("generateVersionJson", VersionJsonTask.class, task -> {
            task.setInput(delayedFile(INSTALL_PROFILE));
            task.setOutput(delayedFile(VERSION_JSON));
            task.dependsOn("generateInstallJson");
        });
        */

        final DelayedJar uni = makeTask("packageUniversal", DelayedJar.class, task -> {
            ArchiveTaskHelper.setClassifier(task, delayedString("B{BUILD_NUM}").call());
            task.getInputs().file(delayedFile(EXTRA_JSON_REL));
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.from(delayedZipTree(BINPATCH_TMP));
            task.from(delayedFileTree(EXTRA_RESOURCES));
            task.from(delayedFileTree(FORGE_RESOURCES));
            task.from(delayedFileTree(FML_RESOURCES));
            task.from(delayedFileTree(EXTRACTED_RES));
            task.from(delayedFile(FML_VERSION));
            task.from(delayedFile(FML_LICENSE));
            task.from(delayedFile(FML_CREDITS));
            task.from(delayedFile(FORGE_LICENSE));
            task.from(delayedFile(FORGE_CREDITS));
            task.from(delayedFile(PAULSCODE_LICENSE1));
            task.from(delayedFile(PAULSCODE_LICENSE2));
            task.from(delayedFile(DEOBF_DATA));
            task.from(delayedFile(CHANGELOG));
            task.from(delayedFile(VERSION_JSON));
            task.exclude("devbinpatches.pack.lzma");
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            task.setIncludeEmptyDirs(false);
            task.setManifest(new Action<Manifest>() {
                @Override
                public void execute(Manifest manifest) {
                    manifest.getAttributes().put("Main-Class", delayedString("{MAIN_CLASS}").call());
                    manifest.getAttributes().put("TweakClass", delayedString("{FML_TWEAK_CLASS}").call());
                    manifest.getAttributes().put("Class-Path", getServerClassPath(delayedFile(EXTRA_JSON_REL).call()));
                }
            });

//            task.doLast(new Action<Task>() {
//                @Override
//                public void execute(Task arg0) {
//                    try {
//                        signJar(((DelayedJar)arg0).getArchivePath(), "forge", "*/*/**", "!paulscode/**");
//                    } catch (Exception e) {
//                        Throwables.propagate(e);
//                    }
//                }
//            });

            ArchiveTaskHelper.setDestinationDir(task, delayedFile("{BUILD_DIR}/distributions").call());
            //uni.dependsOn("genBinPatches", "createChangelog", "createVersionPropertiesFML", "generateVersionJson");
            task.dependsOn("genBinPatches", "createChangelog", "createVersionPropertiesFML");
        }).get();

        project.getArtifacts().add("archives", uni);

        makeTask("generateInstallJson", FileFilterTask.class, task -> {
            task.setInputFile(delayedFile(EXTRA_JSON_REL));
            task.setOutputFile(delayedFile(INSTALL_PROFILE));
            task.addReplacement("@minecraft_version@", delayedString("{MC_VERSION}"));
            task.addReplacement("@version@", delayedString("{VERSION}"));
            task.addReplacement("@project@", delayedString("cauldron"));
            task.addReplacement("@artifact@", delayedString("net.minecraftforge:forge:{MC_VERSION}-{VERSION}"));
            task.addReplacement("@universal_jar@", (Supplier<String>) () -> ArchiveTaskHelper.getArchiveName((AbstractArchiveTask) task.dependsOn("packageUniversal")));
            task.addReplacement("@timestamp@", (Supplier<String>) () -> (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")).format(new Date()));
        });

        Zip inst = makeTask("packageInstaller", Zip.class, task -> {
            ArchiveTaskHelper.setClassifier(task, "installer");
            task.from((Callable<?>) () -> ArchiveTaskHelper.getArchivePath((AbstractArchiveTask) task.dependsOn("packageUniversal")));
            task.from(delayedFile(INSTALL_PROFILE));
            task.from(delayedFile(CHANGELOG));
            task.from(delayedFile(FML_LICENSE));
            task.from(delayedFile(FML_CREDITS));
            task.from(delayedFile(FORGE_LICENSE));
            task.from(delayedFile(FORGE_CREDITS));
            task.from(delayedFile(PAULSCODE_LICENSE1));
            task.from(delayedFile(PAULSCODE_LICENSE2));
            task.from(delayedFile(FORGE_LOGO));
            task.from(delayedZipTree(INSTALLER_BASE), new CopyInto("", "!*.json", "!*.png"));
            task.dependsOn("downloadBaseInstaller", "generateInstallJson");
            task.rename("forge_logo\\.png", "big_logo.png");
            ArchiveTaskHelper.setExtension(task, "jar");
        }).get();

        project.getArtifacts().add("archives", inst);
    }

    @Override
    public void afterEvaluate() {
        super.afterEvaluate();

        this.<SubprojectTask>configureTask("eclipseClean", task -> {
            task.configureProject(getExtension().getSubprojects());
            task.configureProject(getExtension().getCleanProject());
        });

        this.<SubprojectTask>configureTask("eclipseCauldron", task -> {
            task.configureProject(getExtension().getSubprojects());
            task.configureProject(getExtension().getCleanProject());
        });
    }
}
package net.minecraftforge.gradle.dev;

import net.minecraftforge.gradle.ArchiveTaskHelper;
import net.minecraftforge.gradle.CopyInto;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedBase;
import net.minecraftforge.gradle.delayed.DelayedBase.IDelayedResolver;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.tasks.*;
import net.minecraftforge.gradle.tasks.abstractutil.DelayedJar;
import net.minecraftforge.gradle.tasks.abstractutil.ExtractTask;
import net.minecraftforge.gradle.tasks.abstractutil.FileFilterTask;
import net.minecraftforge.gradle.tasks.dev.*;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static net.minecraftforge.gradle.dev.DevConstants.*;

public class ForgeDevPlugin extends DevBasePlugin {
    @Override
    public void applyPlugin() {
        super.applyPlugin();

        // set fmlDir
        getExtension().setFmlDir("fml");

        /* is this even needed for anything....
        // configure genSrg task.
        GenSrgTask genSrgTask = (GenSrgTask) project.getTasks().getByName("genSrgs");
        {
            String[] paths = {DevConstants.FML_RESOURCES, DevConstants.FORGE_RESOURCES};
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
        makeTask("setupForge", task -> {
            task.dependsOn("getVersionJson", "extractForgeSources", "generateProjects", EclipsePlugin.ECLIPSE_TASK_NAME, "copyAssets");
            task.setGroup("Forge");
        });

        // the master task.
        makeTask("buildPackages", task -> {
            task.dependsOn("launch4j", "createChangelog", "packageUniversal", "packageInstaller", "packageUserDev", "packageSrc");
            task.setGroup("Forge");
        });
    }

    protected void createJarProcessTasks() {
        makeTask("deobfuscateJar", ProcessJarTask.class, task -> {
            task.setInJar(delayedFile(Constants.JAR_MERGED));
            task.setOutCleanJar(delayedFile(JAR_SRG_FORGE));
            task.setSrg(delayedFile(NOTCH_2_SRG_SRG));
            task.setExceptorCfg(delayedFile(JOINED_EXC));
            task.setExceptorJson(delayedFile(EXC_JSON));
            task.addTransformerClean(delayedFile(FML_RESOURCES + "/fml_at.cfg"));
            task.addTransformerClean(delayedFile(FORGE_RESOURCES + "/forge_at.cfg"));
            task.setApplyMarkers(true);
            task.dependsOn("downloadMcpTools", "mergeJars", "genSrgs");
        });

        makeTask("decompile", DecompileTask.class, task -> {
            task.setInJar(delayedFile(JAR_SRG_FORGE));
            task.setOutJar(delayedFile(ZIP_DECOMP_FORGE));
            task.setFernFlower(delayedFile(Constants.FERNFLOWER));
            task.setPatch(delayedFile(MCP_PATCH_DIR));
            task.setAstyleConfig(delayedFile(ASTYLE_CFG));
            task.dependsOn("downloadMcpTools", "deobfuscateJar");
        });

        makeTask("fmlPatchJar", ProcessSrcJarTask.class, task -> {
            task.setInJar(delayedFile(ZIP_DECOMP_FORGE));
            task.setOutJar(delayedFile(ZIP_FMLED_FORGE));
            task.addStage("fml", delayedFile(FML_PATCH_DIR), delayedFile(FML_SOURCES), delayedFile(FML_RESOURCES), delayedFile("{FML_CONF_DIR}/patches/Start.java"), delayedFile(DEOBF_DATA), delayedFile(FML_VERSION));
            task.setDoesCache(false);
            task.setMaxFuzz(2);
            task.dependsOn("decompile", "compressDeobfData", "createVersionPropertiesFML");
        });

        makeTask("remapCleanJar", RemapSourcesTask.class, task -> {
            task.setInJar(delayedFile(ZIP_FMLED_FORGE));
            task.setOutJar(delayedFile(REMAPPED_CLEAN));
            task.setMethodsCsv(delayedFile(DevConstants.METHODS_CSV));
            task.setFieldsCsv(delayedFile(DevConstants.FIELDS_CSV));
            task.setParamsCsv(delayedFile(DevConstants.PARAMS_CSV));
            task.setDoesCache(false);
            task.setNoJavadocs();
            task.dependsOn("fmlPatchJar");
        });

        makeTask("forgePatchJar", ProcessSrcJarTask.class, task -> {
            task.setInJar(delayedFile(ZIP_FMLED_FORGE));
            task.setOutJar(delayedFile(ZIP_PATCHED_FORGE));
            task.addStage("forge", delayedFile(FORGE_PATCH_DIR));
            task.setDoesCache(false);
            task.setMaxFuzz(2);
            task.dependsOn("fmlPatchJar");
        });

        makeTask("remapSourcesJar", RemapSourcesTask.class, task -> {
            task.setInJar(delayedFile(ZIP_PATCHED_FORGE));
            task.setOutJar(delayedFile(ZIP_RENAMED_FORGE));
            task.setMethodsCsv(delayedFile(METHODS_CSV));
            task.setFieldsCsv(delayedFile(FIELDS_CSV));
            task.setParamsCsv(delayedFile(PARAMS_CSV));
            task.setDoesCache(false);
            task.setNoJavadocs();
            task.dependsOn("forgePatchJar");
        });
    }

    private void createSourceCopyTasks() {
        makeTask("extractMcResources", ExtractTask.class, task -> {
            task.exclude(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(REMAPPED_CLEAN));
            task.into(delayedFile(ECLIPSE_CLEAN_RES));
            task.dependsOn("extractWorkspace", "remapCleanJar");
        });

        makeTask("extractMcSource", ExtractTask.class, task -> {
            task.include(JAVA_FILES);
            task.setIncludeEmptyDirs(false);
            task.from(delayedFile(REMAPPED_CLEAN));
            task.into(delayedFile(ECLIPSE_CLEAN_SRC));
            task.dependsOn("extractMcResources");
        });

        makeTask("extractForgeResources", ExtractTask.class, task -> {
            task.exclude(JAVA_FILES);
            task.from(delayedFile(ZIP_RENAMED_FORGE));
            task.into(delayedFile(ECLIPSE_FORGE_RES));
            task.dependsOn("remapSourcesJar", "extractWorkspace");
        });

        makeTask("extractForgeSources", ExtractTask.class, task -> {
            task.include(JAVA_FILES);
            task.from(delayedFile(ZIP_RENAMED_FORGE));
            task.into(delayedFile(ECLIPSE_FORGE_SRC));
            task.dependsOn("extractForgeResources");
        });
    }

    private void createProjectTasks() {
        makeTask("createVersionPropertiesFML", FMLVersionPropTask.class, task -> {
            //task.setTasks("createVersionProperties");
            //task.setBuildFile(delayedFile("{FML_DIR}/build.gradle"));
            task.setVersion(() -> FmlDevPlugin.getVersionFromGit(project, new File(delayedString("{FML_DIR}").call())));
            task.setOutputFile(delayedFile(FML_VERSION));
        });

        makeTask("makeStart", CreateStartTask.class, task -> {
            task.addResource("GradleStart.java");
            task.addResource("GradleStartServer.java");
            task.addResource("net/minecraftforge/gradle/GradleStartCommon.java");
            task.addResource("net/minecraftforge/gradle/OldPropertyMapSerializer.java");
            task.addResource("net/minecraftforge/gradle/tweakers/CoremodTweaker.java");
            task.addResource("net/minecraftforge/gradle/tweakers/AccessTransformerTweaker.java");
            task.addReplacement("@@MCVERSION@@", delayedString("{MC_VERSION}"));
            task.addReplacement("@@ASSETINDEX@@", delayedString("{ASSET_INDEX}"));
            task.addReplacement("@@ASSETSDIR@@", delayedFile("{CACHE_DIR}/minecraft/assets"));
            task.addReplacement("@@NATIVESDIR@@", delayedFile(Constants.NATIVES_DIR));
            task.addReplacement("@@SRGDIR@@", delayedFile("{BUILD_DIR}/tmp/"));
            task.addReplacement("@@SRG_NOTCH_SRG@@", delayedFile(DevConstants.NOTCH_2_SRG_SRG));
            task.addReplacement("@@SRG_NOTCH_MCP@@", delayedFile(DevConstants.NOTCH_2_MCP_SRG));
            task.addReplacement("@@SRG_SRG_MCP@@", delayedFile(DevConstants.SRG_2_MCP_SRG));
            task.addReplacement("@@SRG_MCP_SRG@@", delayedFile(DevConstants.MCP_2_SRG_SRG));
            task.addReplacement("@@SRG_MCP_NOTCH@@", delayedFile(DevConstants.MCP_2_NOTCH_SRG));
            task.addReplacement("@@CSVDIR@@", delayedFile("{MCP_DATA_DIR}"));
            task.addReplacement("@@BOUNCERCLIENT@@", delayedString("net.minecraft.launchwrapper.Launch"));
            task.addReplacement("@@BOUNCERSERVER@@", delayedString("net.minecraft.launchwrapper.Launch"));
            task.setStartOut(delayedFile(ECLIPSE_CLEAN_START));
            task.dependsOn("getAssets", "getAssetsIndex", "extractNatives");
        });

        makeTask("generateProjectClean", GenDevProjectsTask.class, task -> {
            task.setTargetDir(delayedFile(ECLIPSE_CLEAN));
            task.addSource(delayedFile(ECLIPSE_CLEAN_START));
            task.setJson(delayedFile(JSON_DEV)); // Change to FmlConstants.JSON_BASE eventually, so that it's the base vanilla json

            task.setMcVersion(delayedString("{MC_VERSION}"));
            task.setMappingChannel(delayedString("{MAPPING_CHANNEL}"));
            task.setMappingVersion(delayedString("{MAPPING_VERSION}"));

            task.dependsOn("extractNatives", "makeStart");
        });

        makeTask("generateProjectForge", GenDevProjectsTask.class, task -> {
            task.setJson(delayedFile(JSON_DEV));
            task.setTargetDir(delayedFile(ECLIPSE_FORGE));

            task.addSource(delayedFile(ECLIPSE_FORGE_SRC));
            task.addSource(delayedFile(FORGE_SOURCES));
            task.addSource(delayedFile(ECLIPSE_CLEAN_START));
            task.addTestSource(delayedFile(FORGE_TEST_SOURCES));

            task.addResource(delayedFile(ECLIPSE_FORGE_RES));
            task.addResource(delayedFile(FORGE_RESOURCES));
            task.addTestResource(delayedFile(FORGE_TEST_RES));

            task.setMcVersion(delayedString("{MC_VERSION}"));
            task.setMappingChannel(delayedString("{MAPPING_CHANNEL}"));
            task.setMappingVersion(delayedString("{MAPPING_VERSION}"));

            task.dependsOn("extractNatives", "createVersionPropertiesFML", "makeStart");
        });

        makeTask("generateProjects", task -> task.dependsOn("generateProjectClean", "generateProjectForge"));
    }

    private void createEclipseTasks() {
        makeTask("eclipseClean", SubprojectTask.class, task -> {
            task.setBuildFile(delayedFile(ECLIPSE_CLEAN + "/build.gradle"));
            task.setTasks(EclipsePlugin.ECLIPSE_TASK_NAME);
            task.dependsOn("extractMcSource", "generateProjects");
        });

        makeTask("eclipseForge", SubprojectTask.class, task -> {
            task.setBuildFile(delayedFile(ECLIPSE_FORGE + "/build.gradle"));
            task.setTasks(EclipsePlugin.ECLIPSE_TASK_NAME);
            task.dependsOn("extractForgeSources", "generateProjects");
        });

        makeTask(EclipsePlugin.ECLIPSE_TASK_NAME, task -> task.dependsOn("eclipseClean", "eclipseForge"));
    }

    private void createMiscTasks() {
        DelayedFile rangeMapClean = delayedFile("{BUILD_DIR}/tmp/rangemapCLEAN.txt");
        DelayedFile rangeMapDirty = delayedFile("{BUILD_DIR}/tmp/rangemapDIRTY.txt");

        makeTask("extractRangeForge", ExtractS2SRangeTask.class, task -> {
            task.setLibsFromProject(delayedFile(ECLIPSE_FORGE + "/build.gradle"), CONFIG_COMPILE, true);
            task.addIn(delayedFile(ECLIPSE_FORGE_SRC));
            task.setExcOutput(delayedFile(EXC_MODIFIERS_DIRTY));
            task.setRangeMap(rangeMapDirty);
        });

        makeTask("retroMapForge", ApplyS2STask.class, task -> {
            task.addIn(delayedFile(ECLIPSE_FORGE_SRC));
            task.setOut(delayedFile(PATCH_DIRTY));
            task.addSrg(delayedFile(MCP_2_SRG_SRG));
            task.addExc(delayedFile(MCP_EXC));
            task.addExc(delayedFile(SRG_EXC)); // just in case
            task.setRangeMap(rangeMapDirty);
            task.setExcModifiers(delayedFile(EXC_MODIFIERS_DIRTY));
            task.dependsOn("genSrgs", "extractRangeForge");

            String[] paths = {DevConstants.FML_RESOURCES, DevConstants.FORGE_RESOURCES};
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
            task.setExcOutput(delayedFile(EXC_MODIFIERS_CLEAN));
            task.setRangeMap(rangeMapClean);
        });

        makeTask("retroMapClean", ApplyS2STask.class, task -> {
            task.addIn(delayedFile(REMAPPED_CLEAN));
            task.setOut(delayedFile(PATCH_CLEAN));
            task.addSrg(delayedFile(MCP_2_SRG_SRG));
            task.addExc(delayedFile(MCP_EXC));
            task.addExc(delayedFile(SRG_EXC)); // just in case
            task.setRangeMap(rangeMapClean);
            task.setExcModifiers(delayedFile(EXC_MODIFIERS_CLEAN));
            task.dependsOn("genSrgs", "extractRangeClean");

            String[] paths = {DevConstants.FML_RESOURCES};
            for (String path : paths) {
                for (File f : project.fileTree(delayedFile(path).call()).getFiles()) {
                    if (f.getPath().endsWith(".exc"))
                        task.addExc(f);
                    else if (f.getPath().endsWith(".srg"))
                        task.addSrg(f);
                }
            }
        });

        makeTask("genPatches", GeneratePatches.class, task -> {
            task.setPatchDir(delayedFile(FORGE_PATCH_DIR));
            task.setOriginal(delayedFile(PATCH_CLEAN)); // was ECLIPSE_CLEAN_SRC
            task.setChanged(delayedFile(PATCH_DIRTY)); // ECLIPSE_FORGE_SRC
            task.setOriginalPrefix("../src-base/minecraft");
            task.setChangedPrefix("../src-work/minecraft");
            task.dependsOn("retroMapForge", "retroMapClean");
            task.setGroup("Forge");
        });

        makeTask("cleanForge", Delete.class, task -> {
            task.delete("eclipse");
            task.setGroup("Clean");
        });

        configureTask(LifecycleBasePlugin.CLEAN_TASK_NAME, task -> task.dependsOn("cleanForge"));

        makeTask("obfuscateJar", ObfuscateTask.class, task -> {
            task.setSrg(delayedFile(MCP_2_NOTCH_SRG));
            task.setExc(delayedFile(JOINED_EXC));
            task.setReverse(false);
            task.setPreFFJar(delayedFile(JAR_SRG_FORGE));
            task.setOutJar(delayedFile(REOBF_TMP));
            task.setBuildFile(delayedFile(ECLIPSE_FORGE + "/build.gradle"));
            task.setMethodsCsv(delayedFile(METHODS_CSV));
            task.setFieldsCsv(delayedFile(FIELDS_CSV));
            task.dependsOn("generateProjects", "extractForgeSources", "genSrgs");
        });

        makeTask("genBinPatches", GenBinaryPatches.class, task -> {
            task.setCleanClient(delayedFile(Constants.JAR_CLIENT_FRESH));
            task.setCleanServer(delayedFile(Constants.JAR_SERVER_FRESH));
            task.setCleanMerged(delayedFile(Constants.JAR_MERGED));
            task.setDirtyJar(delayedFile(REOBF_TMP));
            task.setDeobfDataLzma(delayedFile(DEOBF_DATA));
            task.setOutJar(delayedFile(BINPATCH_TMP));
            task.setSrg(delayedFile(NOTCH_2_SRG_SRG));
            task.addPatchList(delayedFileTree(FORGE_PATCH_DIR));
            task.addPatchList(delayedFileTree(FML_PATCH_DIR));
            task.dependsOn("obfuscateJar", "compressDeobfData");
        });

        makeTask("ciWriteBuildNumber", ForgeVersionReplaceTask.class, task -> {
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.setOutputFile(delayedFile(FORGE_VERSION_JAVA));
            task.setReplacement(delayedString("{BUILD_NUM}"));
        });

        makeTask("fmlChangelog", SubmoduleChangelogTask.class, task -> {
            task.setSubmodule(delayedFile("fml"));
            task.setModuleName("FML");
            task.setPrefix("MinecraftForge/FML");
            task.setOutputFile(project.file("changelog.txt"));
        });
    }

    private void createPackageTasks() {
        makeTask("getLocalizations", CrowdinDownloadTask.class, task -> {
            task.setOutput(delayedFile(CROWDIN_ZIP));
            task.setProjectId(CROWDIN_FORGEID);
            task.setExtract(false);
        });

        makeTask("createChangelog", ChangelogTask.class, task -> {
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.setServerRoot(delayedString("{JENKINS_SERVER}"));
            task.setJobName(delayedString("{JENKINS_JOB}"));
            task.setAuthName(delayedString("{JENKINS_AUTH_NAME}"));
            task.setAuthPassword(delayedString("{JENKINS_AUTH_PASSWORD}"));
            task.setTargetBuild(delayedString("{BUILD_NUM}"));
            task.setOutput(delayedFile(CHANGELOG));
        });

        makeTask("generateVersionJson", VersionJsonTask.class, task -> {
            task.setInput(delayedFile(INSTALL_PROFILE));
            task.setOutput(delayedFile(VERSION_JSON));
            task.dependsOn("generateInstallJson");
        });

        final DelayedJar uni = makeTask("packageUniversal", DelayedJar.class, task -> {
            ArchiveTaskHelper.setClassifier(task, "universal");
            task.getInputs().file(delayedFile(JSON_REL));
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE);
            task.from(delayedZipTree(BINPATCH_TMP));
            task.from(delayedFileTree(FML_RESOURCES));
            task.from(delayedFileTree(FORGE_RESOURCES));
            task.from(delayedZipTree(CROWDIN_ZIP));
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
            task.setIncludeEmptyDirs(false);
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            task.setManifest(new Action<Manifest>() {
                @Override
                public void execute(Manifest manifest) {
                    manifest.getAttributes().put("Main-Class", delayedString("{MAIN_CLASS}").call());
                    manifest.getAttributes().put("TweakClass", delayedString("{FML_TWEAK_CLASS}").call());
                    manifest.getAttributes().put("Class-Path", getServerClassPath(delayedFile(JSON_REL).call()));
                }
            });
            task.doLast(new Action<Task>() {
                @Override
                public void execute(Task arg0) {
                    try {
                        signJar(ArchiveTaskHelper.getArchivePath((DelayedJar) arg0), "forge", "*/*/**", "!paulscode/**");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            ArchiveTaskHelper.setDestinationDir(task, delayedFile("{BUILD_DIR}/distributions").call());
            task.dependsOn("genBinPatches", "getLocalizations", "createChangelog", "createVersionPropertiesFML", "generateVersionJson");
        }).get();

        project.getArtifacts().add("archives", uni);

        makeTask("generateInstallJson", FileFilterTask.class, task -> {
            task.setInputFile(delayedFile(JSON_REL));
            task.setOutputFile(delayedFile(INSTALL_PROFILE));
            task.addReplacement("@minecraft_version@", delayedString("{MC_VERSION}"));
            task.addReplacement("@version@", delayedString("{VERSION}"));
            task.addReplacement("@project@", delayedString("Forge"));
            task.addReplacement("@artifact@", delayedString("net.minecraftforge:forge:{MC_VERSION_SAFE}-{VERSION}"));
            task.addReplacement("@universal_jar@", (Supplier<String>) () -> ArchiveTaskHelper.getArchiveName((AbstractArchiveTask) task.dependsOn("packageUniversal")));
            task.addReplacement("@timestamp@", (Supplier<String>) () -> (new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")).format(new Date()));
        });

        Zip inst = makeTask("packageInstaller", Zip.class, task -> {
            ArchiveTaskHelper.setClassifier(task, "installer");
            task.from((Callable<File>) () -> ArchiveTaskHelper.getArchivePath((AbstractArchiveTask) task.dependsOn("packageUniversal")));
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

        makeTask("zipFmlPatches", Zip.class, task -> {
            task.from(delayedFile(FML_PATCH_DIR));
            ArchiveTaskHelper.setArchiveName(task,"fmlpatches.zip");
            ArchiveTaskHelper.setDestinationDir(task, delayedFile("{BUILD_DIR}/tmp/").call());
        });

        makeTask("zipForgePatches", Zip.class, task -> {
            task.from(delayedFile(FORGE_PATCH_DIR));
            ArchiveTaskHelper.setArchiveName(task,"forgepatches.zip");
            ArchiveTaskHelper.setDestinationDir(task, delayedFile("{BUILD_DIR}/tmp/").call());
        });

        makeTask("jarClasses", Zip.class, task -> {
            task.from(delayedZipTree(BINPATCH_TMP), new CopyInto("", "**/*.class"));
            ArchiveTaskHelper.setArchiveName(task,"binaries.jar");
            ArchiveTaskHelper.setDestinationDir(task, delayedFile("{BUILD_DIR}/tmp/").call());
        });

        makeTask("userDevExtractRange", ExtractS2SRangeTask.class, task -> {
            task.setLibsFromProject(delayedFile(DevConstants.ECLIPSE_FORGE + "/build.gradle"), CONFIG_COMPILE, true);
            task.addIn(delayedFile(DevConstants.FML_SOURCES));
            task.addIn(delayedFile(DevConstants.FORGE_SOURCES));
            task.setRangeMap(delayedFile(DevConstants.USERDEV_RANGEMAP));
            task.dependsOn("extractForgeSources", "generateProjects");
        });

        makeTask("userDevSrgSrc", ApplyS2STask.class, task -> {
            task.addIn(delayedFile(DevConstants.FORGE_SOURCES));
            task.addIn(delayedFile(DevConstants.FML_SOURCES));
            task.setOut(delayedFile(DevConstants.USERDEV_SRG_SRC));
            task.addSrg(delayedFile(DevConstants.MCP_2_SRG_SRG));
            task.addExc(delayedFile(DevConstants.JOINED_EXC));
            task.setRangeMap(delayedFile(DevConstants.USERDEV_RANGEMAP));
            task.dependsOn("genSrgs", "userDevExtractRange");
            task.getOutputs().upToDateWhen(Constants.SPEC_FALSE); //Fucking caching.

            String[] paths = {DevConstants.FML_RESOURCES, DevConstants.FORGE_RESOURCES};
            for (String path : paths) {
                for (File f : project.fileTree(delayedFile(path).call()).getFiles()) {
                    if (f.getPath().endsWith(".exc"))
                        task.addExc(f);
                    else if (f.getPath().endsWith(".srg"))
                        task.addSrg(f);
                }
            }
        });

        Zip userDev = makeTask("packageUserDev", Zip.class, task -> {
            ArchiveTaskHelper.setClassifier(task, "userdev");
            task.from(delayedFile(JSON_DEV));
            task.from((Callable<File>) () -> ArchiveTaskHelper.getArchivePath((AbstractArchiveTask) task.dependsOn("zipFmlPatches")));
            task.from((Callable<File>) () -> ArchiveTaskHelper.getArchivePath((AbstractArchiveTask) task.dependsOn("zipForgePatches")));
            task.from((Callable<File>) () -> ArchiveTaskHelper.getArchivePath((AbstractArchiveTask) task.dependsOn("jarClasses")));
            task.from(delayedFile(CHANGELOG));
            task.from(delayedZipTree(BINPATCH_TMP), new CopyInto("", "devbinpatches.pack.lzma"));
            task.from(delayedFileTree("{FML_DIR}/src/main/resources"), new CopyInto("src/main/resources"));
            task.from(delayedFileTree("src/main/resources"), new CopyInto("src/main/resources"));
            task.from(delayedZipTree(CROWDIN_ZIP), new CopyInto("src/main/resources"));
            task.from(delayedZipTree(DevConstants.USERDEV_SRG_SRC), new CopyInto("src/main/java"));
            task.from(delayedFile(DEOBF_DATA), new CopyInto("src/main/resources/"));
            task.from(delayedFileTree("{FML_CONF_DIR}"), new CopyInto("conf", "astyle.cfg", "exceptor.json", "*.csv", "!packages.csv"));
            task.from(delayedFileTree("{FML_CONF_DIR}/patches"), new CopyInto("conf"));
            task.from(delayedFile(MERGE_CFG), new CopyInto("conf"));
            task.from(delayedFile(NOTCH_2_SRG_SRG), new CopyInto("conf"));
            task.from(delayedFile(SRG_EXC), new CopyInto("conf"));
            task.from(delayedFile(FML_VERSION), new CopyInto("src/main/resources"));
            task.rename(".+-dev\\.json", "dev.json");
            task.rename(".+?\\.srg", "packaged.srg");
            task.rename(".+?\\.exc", "packaged.exc");
            task.setIncludeEmptyDirs(false);
            task.setDuplicatesStrategy(DuplicatesStrategy.EXCLUDE);
            task.dependsOn("packageUniversal", "zipFmlPatches", "createVersionPropertiesFML", "userDevSrgSrc");
            ArchiveTaskHelper.setExtension(task, "jar");
        }).get();

        project.getArtifacts().add("archives", userDev);

        Zip src = makeTask("packageSrc", Zip.class, task -> {
            ArchiveTaskHelper.setClassifier(task, "src");
            task.from(delayedFile(CHANGELOG));
            task.from(delayedFile(FML_LICENSE));
            task.from(delayedFile(FML_CREDITS));
            task.from(delayedFile(FORGE_LICENSE));
            task.from(delayedFile(FORGE_CREDITS));
            task.from(delayedFile("{FML_DIR}/install"), new CopyInto(null, "!*.gradle"));
            task.from(delayedFile("{FML_DIR}/install"), (new CopyInto(null, "*.gradle"))
                    .addExpand("version", delayedString("{MC_VERSION_SAFE}-{VERSION}"))
                    .addExpand("mappings", delayedString("{MAPPING_CHANNEL_DOC}_{MAPPING_VERSION}"))
                    .addExpand("name", "forge"));
            task.from(delayedFile("{FML_DIR}/gradlew"));
            task.from(delayedFile("{FML_DIR}/gradlew.bat"));
            task.from(delayedFile("{FML_DIR}/gradle/wrapper"), new CopyInto("gradle/wrapper"));
            task.rename(".+?\\.gradle", "build.gradle");
            task.dependsOn("createChangelog");
            ArchiveTaskHelper.setExtension(task, "zip");
        }).get();

        project.getArtifacts().add("archives", src);
    }

    public static String getVersionFromJava(Project project, String file) throws IOException {
        String major = "0";
        String minor = "0";
        String revision = "0";
        String build = "0";

        String prefix = "public static final int";
        List<String> lines = Files.readAllLines(project.file(file).toPath());
        for (String s : lines) {
            s = s.trim();
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length(), s.length() - 1);
                s = s.replace('=', ' ').replace("Version", "").replaceAll(" +", " ").trim();
                String[] pts = s.split(" ");

                if (pts[0].equals("major")) major = pts[pts.length - 1];
                else if (pts[0].equals("minor")) minor = pts[pts.length - 1];
                else if (pts[0].equals("revision")) revision = pts[pts.length - 1];
            }
        }

        if (System.getenv().containsKey("BUILD_NUMBER")) {
            build = System.getenv("BUILD_NUMBER");
        }

        String branch;
        if (!System.getenv().containsKey("GIT_BRANCH")) {
            branch = runGit(project, project.getProjectDir(), "rev-parse", "--abbrev-ref", "HEAD");
            if (branch == null) return null;
        } else {
            branch = System.getenv("GIT_BRANCH");
            branch = branch.substring(branch.lastIndexOf('/') + 1);
        }

        if (branch != null && (branch.equals("master") || branch.equals("HEAD"))) {
            branch = null;
        }

        IDelayedResolver<?> resolver = (IDelayedResolver<?>) project.getPlugins().findPlugin("forgedev");
        StringBuilder out = new StringBuilder();

        out.append(DelayedBase.resolve("{MC_VERSION_SAFE}", project, resolver)).append('-'); // Somehow configure this?
        out.append(major).append('.').append(minor).append('.').append(revision).append('.').append(build);
        if (branch != null) {
            out.append('-').append(branch);
        }

        return out.toString();
    }

    @Override
    public void afterEvaluate() {
        super.afterEvaluate();

        this.<SubprojectTask>configureTask("eclipseClean", task -> {
            task.configureProject(getExtension().getSubprojects());
            task.configureProject(getExtension().getCleanProject());
        });

        this.<SubprojectTask>configureTask("eclipseForge", task -> {
            task.configureProject(getExtension().getSubprojects());
            task.configureProject(getExtension().getCleanProject());
        });

        this.<CreateStartTask>configureTask("makeStart", task -> {
            // because different versions of authlib

            String mcVersion = delayedString("{MC_VERSION}").call();

            if (mcVersion.startsWith("1.7")) { // MC 1.7.X
                if (mcVersion.endsWith("10")) { // MC 1.7.10
                    task.addReplacement("//@@USERTYPE@@", "argMap.put(\"userType\", auth.getUserType().getName());");
                    task.addReplacement("//@@USERPROP@@", "argMap.put(\"userProperties\", new GsonBuilder().registerTypeAdapter(com.mojang.authlib.properties.PropertyMap.class, new net.minecraftforge.gradle.OldPropertyMapSerializer()).create().toJson(auth.getUserProperties()));");
                } else {
                    task.removeResource("net/minecraftforge/gradle/OldPropertyMapSerializer.java");
                }

                task.addReplacement("@@CLIENTTWEAKER@@", delayedString("cpw.mods.fml.common.launcher.FMLTweaker"));
                task.addReplacement("@@SERVERTWEAKER@@", delayedString("cpw.mods.fml.common.launcher.FMLServerTweaker"));
            } else { // MC 1.8 +
                task.removeResource("net/minecraftforge/gradle/OldPropertyMapSerializer.java");
                task.addReplacement("//@@USERTYPE@@", "argMap.put(\"userType\", auth.getUserType().getName());");
                task.addReplacement("//@@USERPROP@@", "argMap.put(\"userProperties\", new GsonBuilder().registerTypeAdapter(com.mojang.authlib.properties.PropertyMap.class, new com.mojang.authlib.properties.PropertyMap.Serializer()).create().toJson(auth.getUserProperties()));");

                task.addReplacement("@@CLIENTTWEAKER@@", delayedString("net.minecraftforge.fml.common.launcher.FMLTweaker"));
                task.addReplacement("@@SERVERTWEAKER@@", delayedString("net.minecraftforge.fml.common.launcher.FMLServerTweaker"));
            }
        });
    }
}
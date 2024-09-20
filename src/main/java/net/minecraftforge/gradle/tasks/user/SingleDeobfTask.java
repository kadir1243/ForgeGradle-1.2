package net.minecraftforge.gradle.tasks.user;

import net.md_5.specialsource.Jar;
import net.md_5.specialsource.JarMapping;
import net.md_5.specialsource.JarRemapper;
import net.md_5.specialsource.RemapperProcessor;
import net.md_5.specialsource.provider.ClassLoaderProvider;
import net.md_5.specialsource.provider.JarProvider;
import net.md_5.specialsource.provider.JointProvider;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.FileCollection;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

@CacheableTask
public class SingleDeobfTask extends DefaultTask {
    @InputFile
    private DelayedFile inJar;

    @InputFile
    private DelayedFile srg;

    // getter is marked for input files
    private final List<Object> classpath = new ArrayList<>(5);

    @OutputFile
    private DelayedFile outJar;

    @TaskAction
    public void doTask() throws IOException {
        File in = getInJar();
        File out = getOutJar();
        File mappings = getSrg();

        // load mapping
        JarMapping mapping = new JarMapping();
        mapping.loadMappings(mappings);

        // make a processor out of the ATS and mappings.
        RemapperProcessor srgProcessor = new RemapperProcessor(null, mapping, null);

        // make remapper
        JarRemapper remapper = new JarRemapper(srgProcessor, mapping, null);

        // load jar
        Jar input = Jar.init(in);

        // ensure that inheritance provider is used
        JointProvider inheritanceProviders = new JointProvider();
        inheritanceProviders.add(new JarProvider(input));
        inheritanceProviders.add(new ClassLoaderProvider(new URLClassLoader(Constants.toUrls(getClasspath()))));
        mapping.setFallbackInheritanceProvider(inheritanceProviders);

        // remap jar
        remapper.remapJar(input, out);
    }

    public File getInJar() {
        return inJar.call();
    }

    public void setInJar(DelayedFile inJar) {
        this.inJar = inJar;
    }

    public File getOutJar() {
        return outJar.call();
    }

    public void setOutJar(DelayedFile outJar) {
        this.outJar = outJar;
    }

    public File getSrg() {
        return srg.call();
    }

    public void setSrg(DelayedFile srg) {
        this.srg = srg;
    }

    @InputFiles
    @Optional
    public FileCollection getClasspath() {
        return getProject().files(classpath.toArray());
    }

    /**
     * Whatever works, Closure, file, dir, dependency config.
     * Evaluated with project.file later
     *
     * @param classpathEntry entry
     */
    public void addClasspath(Object classpathEntry) {
        classpath.add(classpathEntry);
    }
}